#define _GNU_SOURCE 1
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#define SPI_OUTPUT_MAX 1500
#define SPI_TOTAL_MS UINT64_C(5000)
#define SPI_OPERATION_MS UINT64_C(100)
#define SPI_CLEANUP_MS UINT64_C(250)
#define SPI_CLOCK UINT32_C(0x81)
#define SPI_BACKUP_MS UINT64_C(600000)
#define SPI_BACKUP_CLEANUP_MS UINT64_C(1000)
#define SPI_ARRAY_BYTES UINT32_C(0x400000)
#define SPI_CHUNK_BYTES UINT32_C(4096)
#define SPI_BACKUP_BLOCK_BYTES UINT32_C(256)
#define SPI_READ_PROBE_MS UINT64_C(30000)
#define SPI_READ_PROBE_BYTES 2048U

static const uint32_t spi_read_probe_addresses[8] = {0, 0x100, 0x200, 0x1000, 0x10000, 0x1ff000, 0x3ff000, 0};

enum spi_reason {
    SPI_OK, SPI_ALREADY_STARTED, SPI_ROOT, SPI_PLATFORM, SPI_KERNEL,
    SPI_REGISTRY, SPI_DEVICE, SPI_MAPPING, SPI_OWNER, SPI_IRQ, SPI_ACCESS,
    SPI_PREFLIGHT, SPI_TIMEOUT, SPI_INTERRUPTED, SPI_COLLISION, SPI_HW_ERROR,
    SPI_ACTIVE, SPI_STATUS_BUSY, SPI_ID_INVALID, SPI_ID_MISMATCH,
    SPI_STATUS_CHANGED, SPI_RESTORE_FAILED, SPI_CLOSE_FAILED, SPI_MEMORY,
    SPI_PREFIX_INVALID, SPI_ARRAY_MISMATCH, SPI_ARRAY_REPETITION
};

enum spi_register {
    R_VERSION, R_CFG, R_GLOBAL, R_TIMING, R_INT, R_INT_EN, R_CMD,
    R_ADDRH, R_ADDRL, R_OP_CFG, R_DATA_NUM, R_OP, R_DMA_LEN, R_OP_CTRL,
    R_OP_PARA, R_COUNT
};

static const uint32_t spi_offsets[R_COUNT] = {
    0xbc, 0x00, 0x04, 0x08, 0x18, 0x1c, 0x24, 0x28,
    0x2c, 0x30, 0x38, 0x3c, 0x40, 0x68, 0x70
};
static const uint32_t spi_baseline[R_COUNT] = {
    0x100, 0x21, 0xc4, 0x6f, 1, 0, 3, 0,
    0x1ff000, 0x30, 0x100, 0x1c4, 0x1000, 0x30000, 0
};
static const enum spi_register spi_restore_order[] = {
    R_CMD, R_ADDRH, R_ADDRL, R_OP_CFG, R_DATA_NUM, R_OP_PARA, R_CFG, R_GLOBAL
};

static int spi_write_allowed(uint32_t offset, uint32_t value) {
    switch (offset) {
        case 0x00: return value == 1 || value == 0x21;
        case 0x04: return value == 0xc0 || value == 0xc4;
        case 0x24: return value == 3 || value == 5 || value == 0x9f;
        case 0x28: return value == 0;
        case 0x2c: return value == 0 || value == 0x1ff000;
        case 0x30: return value == 0 || value == 0x30;
        case 0x38: return value == 1 || value == 8 || value == 0x100;
        case 0x70: return value == 0;
        case 0x3c: return value == 0x85;
        default: return 0;
    }
}

static int spi_write_allowed_backup(uint32_t offset, uint32_t value) {
    if (offset == 0x2c) return value < SPI_ARRAY_BYTES && value % SPI_BACKUP_BLOCK_BYTES == 0;
    if (offset == 0x38 && value == SPI_BACKUP_BLOCK_BYTES) return 1;
    if (offset == 0x3c && value == 0xc5) return 1;
    return spi_write_allowed(offset, value);
}

static int spi_read_probe_address(uint32_t address) {
    unsigned i;
    for (i = 0; i < 8; i++) if (address == spi_read_probe_addresses[i]) return 1;
    return 0;
}

static int spi_write_allowed_read_probe(uint32_t offset, uint32_t value) {
    if (offset == 0x2c) return spi_read_probe_address(value);
    if (offset == 0x38 && value == SPI_CHUNK_BYTES) return 1;
    if (offset == 0x3c && value == 0xc5) return 1;
    return spi_write_allowed(offset, value);
}

static int spi_buffer_allowed(int backup, int read_probe, size_t count) {
    return count == 1 || count == 8 || (read_probe && (count == 256 || count == SPI_CHUNK_BYTES))
            || (backup && count == SPI_BACKUP_BLOCK_BYTES);
}

struct spi_io {
    void *context;
    int (*prepare)(void *, uint64_t);
    int (*clock_read)(void *, uint32_t *);
    int (*register_read)(void *, uint32_t, uint32_t *);
    int (*register_write)(void *, uint32_t, uint32_t);
    int (*buffer_read)(void *, uint8_t *, size_t);
    int (*ownership)(void *);
    uint64_t (*now)(void *);
    int (*pause)(void *, unsigned);
    void (*barrier)(void *, int);
    int (*close)(void *);
};

struct spi_result {
    int reason;
    uint8_t id1[8], id2[8], sr_before, sr_after;
    int have_id1, have_id2, have_before, have_after;
    unsigned commands_started;
    int changed, restored, incomplete;
};

struct spi_run {
    struct spi_io *io;
    struct spi_result *result;
    uint64_t deadline;
    uint64_t cleanup_ms;
    uint32_t expected[R_COUNT];
    int unsafe, array_enabled, read_probe_enabled;
};

static const char *spi_reason_text(int reason) {
    static const char *const names[] = {
        "identity_read", "already_started", "root_invalid", "platform_mismatch",
        "kernel_mismatch", "registry_invalid", "device_invalid", "mapping_failed",
        "controller_owner_unavailable", "controller_irq_present_or_unknown",
        "access_failed", "preflight_mismatch", "timeout", "interrupted",
        "controller_state_changed", "controller_error", "controller_active",
        "flash_status_busy", "jedec_invalid", "jedec_mismatch", "status_changed",
        "restore_failed", "cleanup_failed", "memory_unavailable", "suspicious_prefix",
        "array_mismatch", "suspicious_array_repetition"
    };
    if (reason < 0 || (size_t)reason >= sizeof(names) / sizeof(names[0])) return "access_failed";
    return names[reason];
}

static int spi_time(struct spi_run *run, uint64_t reserve) {
    uint64_t now = run->io->now(run->io->context);
    if (now >= run->deadline || reserve >= run->deadline - now) return SPI_TIMEOUT;
    return SPI_OK;
}

static int spi_clock_check(struct spi_run *run) {
    uint32_t value;
    int reason = spi_time(run, 0);
    if (reason) return reason;
    reason = run->io->clock_read(run->io->context, &value);
    if (reason) return reason;
    reason = spi_time(run, 0);
    if (reason) return reason;
    return value == SPI_CLOCK ? SPI_OK : SPI_PREFLIGHT;
}

static int spi_read_register(struct spi_run *run, enum spi_register reg, uint32_t *value) {
    int reason = spi_time(run, 0);
    if (reason) return reason;
    reason = run->io->register_read(run->io->context, spi_offsets[reg], value);
    if (reason) return reason;
    return spi_time(run, 0);
}

static int spi_owned(struct spi_run *run) {
    uint32_t actual;
    int i, reason = spi_clock_check(run);
    if (reason) return reason;
    for (i = 0; i < R_COUNT; i++) {
        reason = spi_read_register(run, (enum spi_register)i, &actual);
        if (reason) return reason;
        if (i == R_INT && (actual & ~UINT32_C(1))) return SPI_HW_ERROR;
        if ((i == R_OP || i == R_OP_CTRL) && (actual & 1)) return SPI_ACTIVE;
        if (actual != run->expected[i]) return SPI_COLLISION;
    }
    return spi_clock_check(run);
}

static int spi_ownership(struct spi_run *run) {
    int reason = spi_time(run, 0);
    if (reason) return reason;
    reason = run->io->ownership(run->io->context);
    if (reason) return reason;
    return spi_time(run, 0);
}

static int spi_set_config(struct spi_run *run, enum spi_register reg, uint32_t value) {
    uint32_t actual;
    int reason;
    if (reg != R_CFG && reg != R_GLOBAL && reg != R_CMD && reg != R_ADDRH
            && reg != R_ADDRL && reg != R_OP_CFG && reg != R_DATA_NUM && reg != R_OP_PARA) {
        return SPI_ACCESS;
    }
    if (run->expected[reg] == value) return SPI_OK;
    reason = spi_owned(run);
    if (reason) return reason;
    run->result->changed = 1;
    reason = run->io->register_write(run->io->context, spi_offsets[reg], value);
    if (reason) return reason;
    run->io->barrier(run->io->context, 1);
    run->expected[reg] = value;
    reason = spi_read_register(run, reg, &actual);
    if (reason) return reason;
    return actual == value ? SPI_OK : SPI_COLLISION;
}

static int spi_transfer(struct spi_run *run, uint8_t command, uint32_t address, uint8_t *output, size_t count) {
    uint32_t op, flags = 0x84, config = 0;
    uint64_t operation_end;
    int reason;
    if (command == 3) {
        if (run->read_probe_enabled) {
            if (!((count == 256 && address % 256 == 0 && spi_read_probe_address(address))
                    || (count == SPI_CHUNK_BYTES && address == 0))) return SPI_ACCESS;
        } else if (!run->array_enabled || count != SPI_BACKUP_BLOCK_BYTES || address % SPI_BACKUP_BLOCK_BYTES
                || (uint64_t)address + count > SPI_ARRAY_BYTES) return SPI_ACCESS;
        flags = 0xc4;
        config = 0x30;
    } else if (address != 0 || !((command == 0x05 && count == 1) || (command == 0x9f && count == 8))) return SPI_ACCESS;
    reason = spi_time(run, run->cleanup_ms + SPI_OPERATION_MS);
    if (reason) return reason;
    reason = spi_ownership(run);
    if (reason) return reason;
    reason = spi_owned(run);
    if (reason) return reason;
    if ((reason = spi_set_config(run, R_CFG, spi_baseline[R_CFG] & ~UINT32_C(0xe0)))
            || (reason = spi_set_config(run, R_GLOBAL, spi_baseline[R_GLOBAL] & ~UINT32_C(4)))
            || (reason = spi_set_config(run, R_OP_CFG, config))
            || (reason = spi_set_config(run, R_OP_PARA, 0))
            || (reason = spi_set_config(run, R_CMD, command))
            || (reason = spi_set_config(run, R_ADDRH, 0))
            || (reason = spi_set_config(run, R_ADDRL, address))
            || (reason = spi_set_config(run, R_DATA_NUM, (uint32_t)count))) return reason;
    reason = spi_owned(run);
    if (reason) return reason;
    reason = spi_time(run, run->cleanup_ms + SPI_OPERATION_MS);
    if (reason) return reason;
    operation_end = run->io->now(run->io->context);
    if (operation_end > UINT64_MAX - SPI_OPERATION_MS || operation_end >= run->deadline
            || SPI_OPERATION_MS + run->cleanup_ms >= run->deadline - operation_end) return SPI_TIMEOUT;
    operation_end += SPI_OPERATION_MS;
    run->io->barrier(run->io->context, 0);
    run->result->commands_started++;
    reason = run->io->register_write(run->io->context, spi_offsets[R_OP], flags | 1);
    if (reason) return reason;
    run->io->barrier(run->io->context, 1);
    for (;;) {
        if (run->io->now(run->io->context) >= operation_end) return SPI_TIMEOUT;
        reason = spi_read_register(run, R_OP, &op);
        if (reason) return reason;
        if (run->io->now(run->io->context) >= operation_end) return SPI_TIMEOUT;
        if ((op & ~UINT32_C(1)) != flags) return SPI_COLLISION;
        if (!(op & 1)) break;
        reason = run->io->pause(run->io->context, 1);
        if (reason) return reason;
    }
    run->expected[R_OP] = flags;
    run->io->barrier(run->io->context, 1);
    reason = spi_owned(run);
    if (reason) return reason;
    reason = run->io->buffer_read(run->io->context, output, count);
    if (reason) return reason;
    run->io->barrier(run->io->context, 1);
    reason = spi_time(run, run->cleanup_ms);
    if (reason) return reason;
    return spi_owned(run);
}

static int spi_command(struct spi_run *run, uint8_t command, uint8_t *output, size_t count) {
    if (command != 5 && command != 0x9f) return SPI_ACCESS;
    return spi_transfer(run, command, 0, output, count);
}

static int spi_restore(struct spi_run *run) {
    size_t i;
    int reason = spi_ownership(run);
    if (reason) return reason;
    reason = spi_owned(run);
    if (reason) return reason;
    for (i = 0; i < sizeof(spi_restore_order) / sizeof(spi_restore_order[0]); i++) {
        enum spi_register reg = spi_restore_order[i];
        reason = spi_set_config(run, reg, spi_baseline[reg]);
        if (reason) return reason;
    }
    reason = spi_owned(run);
    if (reason) return reason;
    reason = spi_ownership(run);
    if (reason) return reason;
    run->result->restored = 1;
    return SPI_OK;
}

static int spi_valid_id(const uint8_t id[8]) {
    return id[0] != 0 && id[0] != 0xff && id[0] != 0x7f;
}

static void spi_run_identity(struct spi_io *io, struct spi_result *result) {
    struct spi_run run;
    int reason = SPI_OK, close_reason;
    memset(result, 0, sizeof(*result));
    memset(&run, 0, sizeof(run));
    run.io = io;
    run.result = result;
    run.cleanup_ms = SPI_CLEANUP_MS;
    run.deadline = io->now(io->context);
    if (run.deadline > UINT64_MAX - SPI_TOTAL_MS) { reason = SPI_TIMEOUT; goto out; }
    run.deadline += SPI_TOTAL_MS;
    memcpy(run.expected, spi_baseline, sizeof(run.expected));
    reason = io->prepare(io->context, run.deadline);
    if (reason) goto out;
    reason = spi_ownership(&run);
    if (reason) goto out;
    reason = spi_owned(&run);
    if (reason) goto out;
    reason = io->pause(io->context, 100);
    if (reason) goto out;
    reason = spi_ownership(&run);
    if (reason) goto out;
    reason = spi_owned(&run);
    if (reason) goto out;

    reason = spi_command(&run, 0x05, &result->sr_before, 1);
    if (reason) { run.unsafe = 1; goto out; }
    result->have_before = 1;
    if (result->sr_before & 3) { reason = SPI_STATUS_BUSY; goto out; }
    reason = spi_command(&run, 0x9f, result->id1, sizeof(result->id1));
    if (reason) { run.unsafe = 1; goto out; }
    result->have_id1 = 1;
    if (!spi_valid_id(result->id1)) { reason = SPI_ID_INVALID; goto out; }
    reason = spi_command(&run, 0x9f, result->id2, sizeof(result->id2));
    if (reason) { run.unsafe = 1; goto out; }
    result->have_id2 = 1;
    if (!spi_valid_id(result->id2)) { reason = SPI_ID_INVALID; goto out; }
    if (memcmp(result->id1, result->id2, sizeof(result->id1))) { reason = SPI_ID_MISMATCH; goto out; }
    reason = spi_command(&run, 0x05, &result->sr_after, 1);
    if (reason) { run.unsafe = 1; goto out; }
    result->have_after = 1;
    if (result->sr_after & 3) { reason = SPI_STATUS_BUSY; goto out; }
    if (result->sr_before != result->sr_after) { reason = SPI_STATUS_CHANGED; goto out; }
out:
    if (result->changed && !run.unsafe) {
        int restore_reason = spi_restore(&run);
        if (restore_reason) {
            run.unsafe = 1;
            reason = restore_reason == SPI_TIMEOUT ? SPI_TIMEOUT : SPI_RESTORE_FAILED;
        }
    }
    result->incomplete = result->changed && !result->restored;
    close_reason = io->close(io->context);
    if (close_reason) reason = SPI_CLOSE_FAILED;
    if (reason == SPI_OK && io->now(io->context) >= run.deadline) reason = SPI_TIMEOUT;
    result->reason = reason;
}

struct spi_backup_result {
    struct spi_result common;
    unsigned prefix_verified, passes_completed, array_commands, array_bytes_read;
    unsigned status_checks, id_checks, diverse;
    size_t payload_bytes;
};

static const uint8_t spi_expected_id[8] = {0xc2, 0x9e, 0x16, 0xc2, 0x9e, 0x16, 0xc2, 0x9e};

static int spi_backup_status(struct spi_run *run, struct spi_backup_result *result, uint8_t *status) {
    int reason = spi_command(run, 5, status, 1);
    if (reason) { run->unsafe = 1; return reason; }
    result->status_checks++;
    return *status == 0 ? SPI_OK : (*status & 3) ? SPI_STATUS_BUSY : SPI_STATUS_CHANGED;
}

static int spi_backup_id(struct spi_run *run, struct spi_backup_result *result, uint8_t id[8]) {
    int reason = spi_command(run, 0x9f, id, 8);
    if (reason) { run->unsafe = 1; return reason; }
    result->id_checks++;
    return memcmp(id, spi_expected_id, 8) ? SPI_ID_MISMATCH : SPI_OK;
}

static int spi_backup_read(struct spi_run *run, struct spi_backup_result *result,
        uint32_t address, uint8_t output[SPI_BACKUP_BLOCK_BYTES]) {
    unsigned before = run->result->commands_started;
    int reason = spi_transfer(run, 3, address, output, SPI_BACKUP_BLOCK_BYTES);
    result->array_commands += run->result->commands_started - before;
    if (reason) { run->unsafe = 1; return reason; }
    result->array_bytes_read += SPI_BACKUP_BLOCK_BYTES;
    return SPI_OK;
}

static int spi_prefix_valid(const uint8_t *data) {
    size_t i;
    int zero = 1, erased = 1, id3 = 1, id8 = 1;
    for (i = 0; i < SPI_BACKUP_BLOCK_BYTES; i++) {
        zero &= data[i] == 0;
        erased &= data[i] == 0xff;
        id3 &= data[i] == spi_expected_id[i % 3];
        id8 &= data[i] == spi_expected_id[i % 8];
    }
    return !(zero || erased || id3 || id8);
}

static void spi_wipe(uint8_t *data, size_t count) {
    volatile uint8_t *cursor = data;
    if (data) while (count--) *cursor++ = 0;
}

static void spi_run_backup(struct spi_io *io, struct spi_backup_result *result,
        uint8_t *payload, size_t capacity) {
    struct spi_run run;
    struct spi_result *common = &result->common;
    uint8_t scratch[SPI_BACKUP_BLOCK_BYTES], id[8], status;
    uint32_t address;
    unsigned pass;
    int reason = SPI_OK, close_reason;
    memset(result, 0, sizeof(*result));
    memset(&run, 0, sizeof(run));
    run.io = io;
    run.result = common;
    run.cleanup_ms = SPI_BACKUP_CLEANUP_MS;
    run.array_enabled = 1;
    if (!payload || capacity != SPI_ARRAY_BYTES) { reason = SPI_MEMORY; goto out; }
    memset(payload, 0, capacity);
    run.deadline = io->now(io->context);
    if (run.deadline > UINT64_MAX - SPI_BACKUP_MS) { reason = SPI_TIMEOUT; goto out; }
    run.deadline += SPI_BACKUP_MS;
    memcpy(run.expected, spi_baseline, sizeof(run.expected));
    reason = io->prepare(io->context, run.deadline);
    if (reason) goto out;
    if ((reason = spi_ownership(&run)) || (reason = spi_owned(&run))) goto out;
    if ((reason = io->pause(io->context, 100))) goto out;
    if ((reason = spi_ownership(&run)) || (reason = spi_owned(&run))) goto out;

    reason = spi_backup_status(&run, result, &common->sr_before);
    common->have_before = result->status_checks != 0;
    if (reason) goto out;
    reason = spi_backup_id(&run, result, common->id1);
    common->have_id1 = result->id_checks != 0;
    if (reason) goto out;
    reason = spi_backup_id(&run, result, common->id2);
    common->have_id2 = result->id_checks >= 2;
    if (reason) goto out;
    if ((reason = spi_backup_status(&run, result, &status))) goto out;
    if ((reason = spi_backup_read(&run, result, 0, payload))) goto out;
    if ((reason = spi_backup_read(&run, result, 0, scratch))) goto out;
    if (memcmp(payload, scratch, SPI_BACKUP_BLOCK_BYTES)) { reason = SPI_ARRAY_MISMATCH; goto out; }
    if (!spi_prefix_valid(payload)) { reason = SPI_PREFIX_INVALID; goto out; }
    result->prefix_verified = 1;

    for (pass = 0; pass < 2; pass++) {
        for (address = 0; address < SPI_ARRAY_BYTES; address += SPI_BACKUP_BLOCK_BYTES) {
            if ((reason = spi_backup_read(&run, result, address, scratch))) goto out;
            if (pass == 0) {
                if (address == 0 && memcmp(payload, scratch, SPI_BACKUP_BLOCK_BYTES)) {
                    reason = SPI_ARRAY_MISMATCH; goto out;
                }
                memcpy(payload + address, scratch, SPI_BACKUP_BLOCK_BYTES);
                if (address && memcmp(payload, scratch, SPI_BACKUP_BLOCK_BYTES)) result->diverse = 1;
            } else if (memcmp(payload + address, scratch, SPI_BACKUP_BLOCK_BYTES)) {
                reason = SPI_ARRAY_MISMATCH; goto out;
            }
            if ((address + SPI_BACKUP_BLOCK_BYTES) % UINT32_C(0x40000) == 0
                    && (reason = spi_backup_status(&run, result, &status))) goto out;
        }
        result->passes_completed++;
        if (!result->diverse) { reason = SPI_ARRAY_REPETITION; goto out; }
    }
    if ((reason = spi_backup_id(&run, result, id))) goto out;
    if ((reason = spi_backup_id(&run, result, id))) goto out;
    reason = spi_backup_status(&run, result, &common->sr_after);
    common->have_after = result->status_checks == 35;
out:
    if (common->changed && !run.unsafe) {
        int restore_reason = spi_restore(&run);
        if (restore_reason) reason = restore_reason == SPI_TIMEOUT ? SPI_TIMEOUT : SPI_RESTORE_FAILED;
    }
    common->incomplete = common->changed && !common->restored;
    close_reason = io->close(io->context);
    if (close_reason) reason = SPI_CLOSE_FAILED;
    if (reason == SPI_OK && io->now(io->context) >= run.deadline) reason = SPI_TIMEOUT;
    if (reason == SPI_OK && (common->commands_started != 32809 || result->array_commands != 32770
            || result->array_bytes_read != SPI_BACKUP_BLOCK_BYTES * 32770 || result->passes_completed != 2
            || !result->prefix_verified || !result->diverse || result->status_checks != 35
            || result->id_checks != 4 || !common->restored || common->incomplete)) reason = SPI_ACCESS;
    common->reason = reason;
    if (reason == SPI_OK) result->payload_bytes = SPI_ARRAY_BYTES;
    else if (payload && capacity == SPI_ARRAY_BYTES) spi_wipe(payload, capacity);
    spi_wipe(scratch, sizeof(scratch));
}

struct spi_read_probe_buffers {
    uint8_t samples[SPI_READ_PROBE_BYTES];
    uint8_t long_first[SPI_CHUNK_BYTES], long_second[SPI_CHUNK_BYTES];
};

struct spi_read_probe_result {
    struct spi_backup_result progress;
    unsigned sample_count, sample_bytes;
    int short_repeat, long_repeat, overlap[3], period_256, period_512, long_present;
    uint8_t long_head[32], long_mid[32], long_tail[32];
};

static int spi_period_equal(const uint8_t data[SPI_CHUNK_BYTES], size_t period) {
    size_t i;
    for (i = period; i < SPI_CHUNK_BYTES; i++) if (data[i] != data[i % period]) return 0;
    return 1;
}

static int spi_read_probe_transfer(struct spi_run *run, struct spi_read_probe_result *result,
        uint32_t address, uint8_t *output, size_t size) {
    unsigned before = run->result->commands_started;
    int reason = spi_transfer(run, 3, address, output, size);
    result->progress.array_commands += run->result->commands_started - before;
    if (reason) { run->unsafe = 1; return reason; }
    result->progress.array_bytes_read += (unsigned)size;
    return SPI_OK;
}

static void spi_run_read_probe(struct spi_io *io, struct spi_read_probe_result *result,
        struct spi_read_probe_buffers *buffers) {
    struct spi_run run;
    struct spi_backup_result *progress = &result->progress;
    struct spi_result *common = &progress->common;
    uint8_t id[8], status;
    unsigned i;
    int reason = SPI_OK, close_reason;
    memset(result, 0, sizeof(*result));
    memset(&run, 0, sizeof(run));
    result->short_repeat = result->long_repeat = result->period_256 = result->period_512 = -1;
    result->overlap[0] = result->overlap[1] = result->overlap[2] = -1;
    run.io = io; run.result = common;
    run.cleanup_ms = SPI_BACKUP_CLEANUP_MS;
    run.read_probe_enabled = 1;
    if (!buffers) { reason = SPI_MEMORY; goto out; }
    memset(buffers, 0, sizeof(*buffers));
    run.deadline = io->now(io->context);
    if (run.deadline > UINT64_MAX - SPI_READ_PROBE_MS) { reason = SPI_TIMEOUT; goto out; }
    run.deadline += SPI_READ_PROBE_MS;
    memcpy(run.expected, spi_baseline, sizeof(run.expected));
    reason = io->prepare(io->context, run.deadline);
    if (reason) goto out;
    if ((reason = spi_ownership(&run)) || (reason = spi_owned(&run))) goto out;
    if ((reason = io->pause(io->context, 100))) goto out;
    if ((reason = spi_ownership(&run)) || (reason = spi_owned(&run))) goto out;

    reason = spi_backup_status(&run, progress, &common->sr_before);
    common->have_before = progress->status_checks != 0;
    if (reason) goto out;
    reason = spi_backup_id(&run, progress, common->id1);
    common->have_id1 = progress->id_checks != 0;
    if (reason) goto out;
    reason = spi_backup_id(&run, progress, common->id2);
    common->have_id2 = progress->id_checks >= 2;
    if (reason) goto out;
    if ((reason = spi_backup_status(&run, progress, &status))) goto out;
    for (i = 0; i < 8; i++) {
        reason = spi_read_probe_transfer(&run, result, spi_read_probe_addresses[i], buffers->samples + i * 256, 256);
        if (reason) goto out;
        result->sample_count++;
        result->sample_bytes += 256;
    }
    result->short_repeat = memcmp(buffers->samples, buffers->samples + 7 * 256, 256) == 0;
    if ((reason = spi_read_probe_transfer(&run, result, 0, buffers->long_first, SPI_CHUNK_BYTES))) goto out;
    if ((reason = spi_read_probe_transfer(&run, result, 0, buffers->long_second, SPI_CHUNK_BYTES))) goto out;
    result->long_repeat = memcmp(buffers->long_first, buffers->long_second, SPI_CHUNK_BYTES) == 0;
    for (i = 0; i < 3; i++) {
        result->overlap[i] = memcmp(buffers->samples + i * 256, buffers->long_first + i * 256, 256) == 0;
    }
    result->period_256 = spi_period_equal(buffers->long_first, 256);
    result->period_512 = spi_period_equal(buffers->long_first, 512);
    if ((reason = spi_backup_status(&run, progress, &status))) goto out;
    if ((reason = spi_backup_id(&run, progress, id))) goto out;
    if ((reason = spi_backup_id(&run, progress, id))) goto out;
    reason = spi_backup_status(&run, progress, &common->sr_after);
    common->have_after = progress->status_checks == 4;
out:
    if (common->changed && !run.unsafe) {
        int restore_reason = spi_restore(&run);
        if (restore_reason) reason = restore_reason == SPI_TIMEOUT ? SPI_TIMEOUT : SPI_RESTORE_FAILED;
    }
    common->incomplete = common->changed && !common->restored;
    close_reason = io->close(io->context);
    if (close_reason) reason = SPI_CLOSE_FAILED;
    if (reason == SPI_OK && io->now(io->context) >= run.deadline) reason = SPI_TIMEOUT;
    if (reason == SPI_OK && (common->commands_started != 18 || progress->array_commands != 10
            || progress->array_bytes_read != 10240 || result->sample_count != 8 || result->sample_bytes != 2048
            || progress->status_checks != 4 || progress->id_checks != 4 || !common->restored || common->incomplete)) reason = SPI_ACCESS;
    common->reason = reason;
    if (reason == SPI_OK) {
        progress->payload_bytes = SPI_READ_PROBE_BYTES;
        result->long_present = 1;
        memcpy(result->long_head, buffers->long_first, 32);
        memcpy(result->long_mid, buffers->long_first + 0x800, 32);
        memcpy(result->long_tail, buffers->long_first + 0xfe0, 32);
    } else if (buffers) spi_wipe((uint8_t *)buffers, sizeof(*buffers));
}

static void spi_hex(char *out, const uint8_t *data, size_t count, int present) {
    static const char digits[] = "0123456789abcdef";
    size_t i;
    if (!present) { memcpy(out, "unknown", 8); return; }
    for (i = 0; i < count; i++) {
        out[i * 2] = digits[data[i] >> 4];
        out[i * 2 + 1] = digits[data[i] & 15];
    }
    out[count * 2] = '\0';
}

static void spi_format(const struct spi_result *result, char output[SPI_OUTPUT_MAX]) {
    char id1[17], id2[17], before[8], after[8];
    spi_hex(id1, result->id1, 8, result->have_id1);
    spi_hex(id2, result->id2, 8, result->have_id2);
    spi_hex(before, &result->sr_before, 1, result->have_before);
    spi_hex(after, &result->sr_after, 1, result->have_after);
    snprintf(output, SPI_OUTPUT_MAX,
            "version=1\nstatus=%s\nreason=%s\nid1=%s\nid2=%s\nsr_before=%s\nsr_after=%s\n"
            "commands_started=%u\nconfig_restored=%s\nrestoration_incomplete=%d\n"
            "flash_array_read=not_performed\nflash_program_erase=not_performed\nownership=not_established\n"
            "interrupt_buffer_preserved=not_guaranteed\nop_flags_left=not_restored\n",
            result->reason == SPI_OK ? "ok" : "error", spi_reason_text(result->reason),
            id1, id2, before, after, result->commands_started,
            !result->changed ? "not_changed" : result->restored ? "1" : "0", result->incomplete);
}

static void spi_format_backup(const struct spi_backup_result *result, char output[SPI_OUTPUT_MAX]) {
    const struct spi_result *common = &result->common;
    char id1[17], id2[17], before[8], after[8];
    spi_hex(id1, common->id1, 8, common->have_id1);
    spi_hex(id2, common->id2, 8, common->have_id2);
    spi_hex(before, &common->sr_before, 1, common->have_before);
    spi_hex(after, &common->sr_after, 1, common->have_after);
    snprintf(output, SPI_OUTPUT_MAX,
            "version=2\nstatus=%s\nreason=%s\nid1=%s\nid2=%s\nsr_before=%s\nsr_after=%s\n"
            "commands_started=%u\nconfig_restored=%s\nrestoration_incomplete=%d\n"
            "flash_array_read=%s\nflash_program_erase=not_performed\nownership=not_established\n"
            "interrupt_buffer_preserved=not_guaranteed\nop_flags_left=not_restored\n"
            "flash_capacity=4194304\nread_chunk_bytes=256\npayload_bytes=%u\nprefix_verified=%u\npasses_completed=%u\n"
            "array_commands=%u\narray_bytes_read=%u\nstatus_checks=%u\nid_checks=%u\naddress_diversity=%u\n",
            common->reason == SPI_OK ? "ok" : "error", common->reason == SPI_OK ? "array_read_verified" : spi_reason_text(common->reason),
            id1, id2, before, after, common->commands_started,
            !common->changed ? "not_changed" : common->restored ? "1" : "0", common->incomplete,
            common->reason == SPI_OK ? "verified_two_pass" : result->array_commands ? "incomplete" : "not_performed",
            (unsigned)result->payload_bytes, result->prefix_verified, result->passes_completed,
            result->array_commands, result->array_bytes_read, result->status_checks, result->id_checks, result->diverse);
}

static const char *spi_comparison(int value) { return value < 0 ? "unknown" : value ? "1" : "0"; }

static void spi_format_read_probe(const struct spi_read_probe_result *result, char output[SPI_OUTPUT_MAX]) {
    const struct spi_backup_result *progress = &result->progress;
    const struct spi_result *common = &progress->common;
    char id1[17], id2[17], before[8], after[8], head[65], mid[65], tail[65];
    spi_hex(id1, common->id1, 8, common->have_id1);
    spi_hex(id2, common->id2, 8, common->have_id2);
    spi_hex(before, &common->sr_before, 1, common->have_before);
    spi_hex(after, &common->sr_after, 1, common->have_after);
    spi_hex(head, result->long_head, 32, result->long_present);
    spi_hex(mid, result->long_mid, 32, result->long_present);
    spi_hex(tail, result->long_tail, 32, result->long_present);
    snprintf(output, SPI_OUTPUT_MAX,
            "version=1\nstatus=%s\nreason=%s\nid1=%s\nid2=%s\nsr_before=%s\nsr_after=%s\n"
            "commands_started=%u\nconfig_restored=%s\nrestoration_incomplete=%d\n"
            "flash_array_read=%s\nflash_program_erase=not_performed\nownership=not_established\n"
            "interrupt_buffer_preserved=not_guaranteed\nop_flags_left=not_restored\n"
            "payload_bytes=%u\nsample_count=%u\nsample_bytes=%u\narray_commands=%u\narray_bytes_read=%u\n"
            "status_checks=%u\nid_checks=%u\nshort_repeat_equal=%s\nlong_repeat_equal=%s\n"
            "overlap_0_equal=%s\noverlap_100_equal=%s\noverlap_200_equal=%s\n"
            "long_period_256=%s\nlong_period_512=%s\nlong_head32_hex=%s\nlong_mid32_hex=%s\nlong_tail32_hex=%s\n"
            "address_space_mode=unverified\n",
            common->reason == SPI_OK ? "ok" : "error", common->reason == SPI_OK ? "read_probe_complete" : spi_reason_text(common->reason),
            id1, id2, before, after, common->commands_started,
            !common->changed ? "not_changed" : common->restored ? "1" : "0", common->incomplete,
            common->reason == SPI_OK ? "sampled_addresses" : progress->array_commands ? "incomplete" : "not_performed",
            (unsigned)progress->payload_bytes, result->sample_count, result->sample_bytes,
            progress->array_commands, progress->array_bytes_read, progress->status_checks, progress->id_checks,
            spi_comparison(result->short_repeat), spi_comparison(result->long_repeat),
            spi_comparison(result->overlap[0]), spi_comparison(result->overlap[1]), spi_comparison(result->overlap[2]),
            spi_comparison(result->period_256), spi_comparison(result->period_512), head, mid, tail);
}

static volatile int spi_started;
static int spi_claim_once(void) { return __sync_lock_test_and_set(&spi_started, 1) == 0; }

#ifndef SPI_IDENTITY_HOST_TEST
#if !defined(__arm__) || !defined(__ARM_ARCH) || __ARM_ARCH < 7
#error Production SPI identity probe requires ARMv7 or newer AArch32
#endif
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>

#define SPI_PAGE 4096U
#define SPI_PLATFORM_PATH "/sys/bus/platform/devices/f9950000.hifmc100.NAND"

struct spi_native {
    int descriptor;
    void *clock_map, *register_map, *buffer_map;
    uint64_t deadline;
    int backup, read_probe;
};

static uint64_t native_now(void *context) {
    struct timespec value;
    (void)context;
    if (clock_gettime(CLOCK_MONOTONIC, &value) || value.tv_sec < 0 || value.tv_nsec < 0
            || value.tv_nsec >= 1000000000 || (uint64_t)value.tv_sec > (UINT64_MAX - 999) / 1000) return UINT64_MAX;
    return (uint64_t)value.tv_sec * 1000 + (uint64_t)value.tv_nsec / 1000000;
}

static int native_deadline(struct spi_native *context) {
    return native_now(context) < context->deadline ? SPI_OK : SPI_TIMEOUT;
}

static int native_pause(void *context, unsigned millis) {
    struct timespec delay;
    (void)context;
    delay.tv_sec = millis / 1000;
    delay.tv_nsec = (long)(millis % 1000) * 1000000;
    if (nanosleep(&delay, NULL)) return errno == EINTR ? SPI_INTERRUPTED : SPI_ACCESS;
    return SPI_OK;
}

static void native_barrier(void *context, int full) {
    (void)context;
    if (full) __asm__ __volatile__("dsb sy" ::: "memory");
    else __asm__ __volatile__("dsb st" ::: "memory");
}

static int native_text(struct spi_native *context, const char *path, char *output, size_t capacity) {
    int fd, reason = SPI_OK;
    ssize_t count;
    size_t used = 0;
    if (native_deadline(context)) return SPI_TIMEOUT;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return SPI_ACCESS;
    for (;;) {
        if (native_deadline(context)) { reason = SPI_TIMEOUT; break; }
        count = read(fd, output + used, capacity - used);
        if (count < 0) { reason = errno == EINTR ? SPI_INTERRUPTED : SPI_ACCESS; break; }
        if (!count) { output[used] = '\0'; break; }
        used += (size_t)count;
        if (used == capacity) { reason = SPI_ACCESS; break; }
    }
    if (close(fd)) reason = SPI_CLOSE_FAILED;
    return reason;
}

static int native_same_node(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_rdev == b->st_rdev
            && a->st_mode == b->st_mode && a->st_uid == b->st_uid && a->st_gid == b->st_gid;
}

static uint64_t native_major(uint64_t value) { return ((value >> 8) & 0xfff) | ((value >> 32) & UINT64_C(0xfffff000)); }
static uint64_t native_minor(uint64_t value) { return (value & 0xff) | ((value >> 12) & UINT64_C(0xffffff00)); }

static int native_contains(const char *text, const char *needle) {
    const char *start;
    for (start = text; *start; start++) {
        size_t i;
        for (i = 0; needle[i] && start[i]; i++) {
            char ch = start[i];
            if (ch >= 'A' && ch <= 'Z') ch += 'a' - 'A';
            if (ch != needle[i]) break;
        }
        if (!needle[i]) return 1;
    }
    return 0;
}

static int native_ownership(void *opaque) {
    struct spi_native *context = opaque;
    struct stat node;
    char text[65537];
    int reason;
    if (native_deadline(context)) return SPI_TIMEOUT;
    if (stat(SPI_PLATFORM_PATH, &node) || !S_ISDIR(node.st_mode)) return SPI_OWNER;
    if (lstat(SPI_PLATFORM_PATH "/driver", &node) == 0 || errno != ENOENT) return SPI_OWNER;
    reason = native_text(context, "/proc/interrupts", text, sizeof(text) - 1);
    if (reason) return reason;
    if (!text[0] || !strstr(text, "CPU") || native_contains(text, "hifmc")
            || native_contains(text, "hisfc") || native_contains(text, "hi_sfc")) return SPI_IRQ;
    return native_deadline(context);
}

static int native_property(const char *key, const char *expected) {
    char value[PROP_VALUE_MAX];
    int length = __system_property_get(key, value);
    return length > 0 && strcmp(value, expected) == 0;
}

static int native_prepare(void *opaque, uint64_t deadline) {
    struct spi_native *context = opaque;
    struct stat before, opened, after;
    char text[65537], name[64], extra, *line, *save;
    unsigned major_value, minor_value, registry_minor = 0, found = 0;
    unsigned uid0, uid1, uid2, uid3;
    int reason;
    context->deadline = deadline;
    if (getuid() != 0 || geteuid() != 0) return SPI_ROOT;
    reason = native_text(context, "/proc/self/status", text, sizeof(text) - 1);
    if (reason) return reason;
    found = 0;
    for (line = strtok_r(text, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        if (strncmp(line, "Uid:", 4) != 0) continue;
        if (++found != 1 || sscanf(line + 4, "%u %u %u %u %c", &uid0, &uid1, &uid2, &uid3, &extra) != 4
                || uid0 || uid1 || uid2 || uid3) return SPI_ROOT;
    }
    if (found != 1) return SPI_ROOT;
    if (!native_property("ro.product.model", "CT1105_SB") || !native_property("ro.hardware", "bigfish")
            || !native_property("ro.build.version.sdk", "22")
            || !native_property("ro.product.cpu.abi", "armeabi-v7a")) return SPI_PLATFORM;
    reason = native_text(context, "/proc/sys/kernel/osrelease", text, 255);
    if (reason) return reason;
    if (strcmp(text, "3.18.24_hi3798cv2x\n") && strcmp(text, "3.18.24_hi3798cv2x")) return SPI_KERNEL;
    if (sysconf(_SC_PAGESIZE) != SPI_PAGE) return SPI_MAPPING;
    reason = native_text(context, "/proc/misc", text, sizeof(text) - 1);
    if (reason) return reason;
    found = 0;
    for (line = strtok_r(text, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        if (sscanf(line, "%u %63s %c", &minor_value, name, &extra) != 2) return SPI_REGISTRY;
        if (!strcmp(name, "hi_mem")) {
            if (++found != 1 || minor_value > 255) return SPI_REGISTRY;
            registry_minor = minor_value;
        }
    }
    if (found != 1) return SPI_REGISTRY;
    reason = native_text(context, "/sys/class/misc/hi_mem/dev", text, 63);
    if (reason) return reason;
    if (sscanf(text, "%u:%u %c", &major_value, &minor_value, &extra) != 2
            || major_value != 10 || minor_value != registry_minor) return SPI_REGISTRY;
    reason = native_ownership(context);
    if (reason) return reason;
    if (lstat("/dev/hi_mem", &before) || !S_ISCHR(before.st_mode)
            || (before.st_uid != 0 && before.st_uid != 1000)
            || native_major((uint64_t)before.st_rdev) != 10
            || native_minor((uint64_t)before.st_rdev) != registry_minor) return SPI_DEVICE;
    context->descriptor = open("/dev/hi_mem", O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (context->descriptor < 0) return SPI_DEVICE;
    if (fstat(context->descriptor, &opened) || lstat("/dev/hi_mem", &after)
            || !native_same_node(&before, &opened) || !native_same_node(&opened, &after)) return SPI_DEVICE;
    if (native_deadline(context)) return SPI_TIMEOUT;
    context->clock_map = mmap64(NULL, SPI_PAGE, PROT_READ, MAP_SHARED, context->descriptor, (off64_t)UINT64_C(0xf8a22000));
    if (context->clock_map == MAP_FAILED) return SPI_MAPPING;
    if (native_deadline(context)) return SPI_TIMEOUT;
    return SPI_OK;
}

static int native_clock_read(void *opaque, uint32_t *value) {
    struct spi_native *context = opaque;
    if (context->clock_map == MAP_FAILED) return SPI_ACCESS;
    *value = *(volatile const uint32_t *)((const uint8_t *)context->clock_map + 0x380);
    native_barrier(context, 1);
    return SPI_OK;
}

static int native_map_controller(struct spi_native *context) {
    uint32_t clock;
    if (native_clock_read(context, &clock) || clock != SPI_CLOCK) return SPI_PREFLIGHT;
    if (native_deadline(context)) return SPI_TIMEOUT;
    if (context->register_map == MAP_FAILED) {
        context->register_map = mmap64(NULL, SPI_PAGE, PROT_READ | PROT_WRITE, MAP_SHARED,
                context->descriptor, (off64_t)UINT64_C(0xf9950000));
        if (context->register_map == MAP_FAILED) return SPI_MAPPING;
    }
    if (context->buffer_map == MAP_FAILED) {
        context->buffer_map = mmap64(NULL, SPI_PAGE, PROT_READ, MAP_SHARED,
                context->descriptor, (off64_t)UINT64_C(0xfe200000));
        if (context->buffer_map == MAP_FAILED) return SPI_MAPPING;
    }
    return native_deadline(context);
}

static int native_register_read(void *opaque, uint32_t offset, uint32_t *value) {
    struct spi_native *context = opaque;
    int i, allowed = 0, reason;
    for (i = 0; i < R_COUNT; i++) if (offset == spi_offsets[i]) allowed = 1;
    if (!allowed) return SPI_ACCESS;
    if (context->register_map == MAP_FAILED) {
        reason = native_map_controller(context);
        if (reason) return reason;
    }
    *value = *(volatile const uint32_t *)((const uint8_t *)context->register_map + offset);
    native_barrier(context, 1);
    return SPI_OK;
}

static int native_register_write(void *opaque, uint32_t offset, uint32_t value) {
    struct spi_native *context = opaque;
    if (context->register_map == MAP_FAILED || context->buffer_map == MAP_FAILED) return SPI_ACCESS;
    if (!(context->read_probe ? spi_write_allowed_read_probe(offset, value)
            : context->backup ? spi_write_allowed_backup(offset, value) : spi_write_allowed(offset, value))) return SPI_ACCESS;
    if (native_deadline(context)) return SPI_TIMEOUT;
    native_barrier(context, 0);
    *(volatile uint32_t *)((uint8_t *)context->register_map + offset) = value;
    return SPI_OK;
}

static int native_buffer_read(void *opaque, uint8_t *output, size_t count) {
    struct spi_native *context = opaque;
    uint32_t word;
    size_t i;
    if (context->buffer_map == MAP_FAILED || !spi_buffer_allowed(context->backup, context->read_probe, count)) return SPI_ACCESS;
    native_barrier(context, 1);
    for (i = 0; i < count; i += 4) {
        size_t byte;
        word = *((volatile const uint32_t *)context->buffer_map + i / 4);
        for (byte = 0; byte < 4 && i + byte < count; byte++) output[i + byte] = (uint8_t)(word >> (byte * 8));
    }
    return SPI_OK;
}

static int native_close(void *opaque) {
    struct spi_native *context = opaque;
    int failed = 0;
    if (context->buffer_map != MAP_FAILED && munmap(context->buffer_map, SPI_PAGE)) failed = 1;
    if (context->register_map != MAP_FAILED && munmap(context->register_map, SPI_PAGE)) failed = 1;
    if (context->clock_map != MAP_FAILED && munmap(context->clock_map, SPI_PAGE)) failed = 1;
    context->buffer_map = context->register_map = context->clock_map = MAP_FAILED;
    if (context->descriptor >= 0 && close(context->descriptor)) failed = 1;
    context->descriptor = -1;
    return failed ? SPI_CLOSE_FAILED : SPI_OK;
}

JNIEXPORT jstring JNICALL Java_net_quber_afactorymode_SpiIdentityProbe_runIdentity(JNIEnv *env, jclass type) {
    struct spi_native context;
    struct spi_io io;
    struct spi_result result;
    char output[SPI_OUTPUT_MAX];
    (void)type;
    memset(&context, 0, sizeof(context));
    memset(&result, 0, sizeof(result));
    context.descriptor = -1;
    context.clock_map = context.register_map = context.buffer_map = MAP_FAILED;
    if (!spi_claim_once()) result.reason = SPI_ALREADY_STARTED;
    else {
        io.context = &context;
        io.prepare = native_prepare;
        io.clock_read = native_clock_read;
        io.register_read = native_register_read;
        io.register_write = native_register_write;
        io.buffer_read = native_buffer_read;
        io.ownership = native_ownership;
        io.now = native_now;
        io.pause = native_pause;
        io.barrier = native_barrier;
        io.close = native_close;
        spi_run_identity(&io, &result);
    }
    spi_format(&result, output);
    return (*env)->NewStringUTF(env, output);
}

JNIEXPORT jobjectArray JNICALL Java_net_quber_afactorymode_SpiBackupProbe_runBackup(JNIEnv *env, jclass type) {
    struct spi_native context;
    struct spi_io io;
    struct spi_backup_result result;
    char report[SPI_OUTPUT_MAX];
    uint8_t *payload = NULL;
    jclass byte_array_class;
    jobjectArray response;
    jbyteArray empty, full, report_bytes;
    size_t report_length;
    (void)type;
    byte_array_class = (*env)->FindClass(env, "[B");
    if (!byte_array_class) return NULL;
    response = (*env)->NewObjectArray(env, 2, byte_array_class, NULL);
    if (!response) return NULL;
    empty = (*env)->NewByteArray(env, 0);
    if (!empty) return NULL;
    full = (*env)->NewByteArray(env, SPI_ARRAY_BYTES);
    if (!full) return NULL;
    memset(&context, 0, sizeof(context));
    memset(&result, 0, sizeof(result));
    context.descriptor = -1;
    context.backup = 1;
    context.clock_map = context.register_map = context.buffer_map = MAP_FAILED;
    if (!spi_claim_once()) result.common.reason = SPI_ALREADY_STARTED;
    else {
        payload = malloc(SPI_ARRAY_BYTES);
        io.context = &context;
        io.prepare = native_prepare;
        io.clock_read = native_clock_read;
        io.register_read = native_register_read;
        io.register_write = native_register_write;
        io.buffer_read = native_buffer_read;
        io.ownership = native_ownership;
        io.now = native_now;
        io.pause = native_pause;
        io.barrier = native_barrier;
        io.close = native_close;
        spi_run_backup(&io, &result, payload, SPI_ARRAY_BYTES);
    }
    spi_format_backup(&result, report);
    report_length = strlen(report);
    report_bytes = (*env)->NewByteArray(env, (jsize)report_length);
    if (!report_bytes) { spi_wipe(payload, SPI_ARRAY_BYTES); free(payload); return NULL; }
    (*env)->SetByteArrayRegion(env, report_bytes, 0, (jsize)report_length, (const jbyte *)report);
    if (result.payload_bytes == SPI_ARRAY_BYTES && payload) {
        (*env)->SetByteArrayRegion(env, full, 0, SPI_ARRAY_BYTES, (const jbyte *)payload);
    }
    spi_wipe(payload, SPI_ARRAY_BYTES);
    free(payload);
    if ((*env)->ExceptionCheck(env)) return NULL;
    (*env)->SetObjectArrayElement(env, response, 0, report_bytes);
    (*env)->SetObjectArrayElement(env, response, 1, result.payload_bytes == SPI_ARRAY_BYTES ? full : empty);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return response;
}

JNIEXPORT jobjectArray JNICALL Java_net_quber_afactorymode_SpiReadProbe_runReadProbe(JNIEnv *env, jclass type) {
    struct spi_native context;
    struct spi_io io;
    struct spi_read_probe_result result;
    struct spi_read_probe_buffers *buffers = NULL;
    char report[SPI_OUTPUT_MAX];
    jclass byte_array_class;
    jobjectArray response;
    jbyteArray empty, full, report_bytes;
    size_t report_length;
    (void)type;
    byte_array_class = (*env)->FindClass(env, "[B");
    if (!byte_array_class) return NULL;
    response = (*env)->NewObjectArray(env, 2, byte_array_class, NULL);
    if (!response) return NULL;
    empty = (*env)->NewByteArray(env, 0);
    if (!empty) return NULL;
    full = (*env)->NewByteArray(env, SPI_READ_PROBE_BYTES);
    if (!full) return NULL;
    memset(&context, 0, sizeof(context));
    memset(&result, 0, sizeof(result));
    result.short_repeat = result.long_repeat = result.period_256 = result.period_512 = -1;
    result.overlap[0] = result.overlap[1] = result.overlap[2] = -1;
    context.descriptor = -1;
    context.read_probe = 1;
    context.clock_map = context.register_map = context.buffer_map = MAP_FAILED;
    if (!spi_claim_once()) result.progress.common.reason = SPI_ALREADY_STARTED;
    else {
        buffers = malloc(sizeof(*buffers));
        io.context = &context;
        io.prepare = native_prepare;
        io.clock_read = native_clock_read;
        io.register_read = native_register_read;
        io.register_write = native_register_write;
        io.buffer_read = native_buffer_read;
        io.ownership = native_ownership;
        io.now = native_now;
        io.pause = native_pause;
        io.barrier = native_barrier;
        io.close = native_close;
        spi_run_read_probe(&io, &result, buffers);
    }
    spi_format_read_probe(&result, report);
    report_length = strlen(report);
    report_bytes = (*env)->NewByteArray(env, (jsize)report_length);
    if (!report_bytes) { spi_wipe((uint8_t *)buffers, sizeof(*buffers)); free(buffers); return NULL; }
    (*env)->SetByteArrayRegion(env, report_bytes, 0, (jsize)report_length, (const jbyte *)report);
    if (result.progress.payload_bytes == SPI_READ_PROBE_BYTES && buffers) {
        (*env)->SetByteArrayRegion(env, full, 0, SPI_READ_PROBE_BYTES, (const jbyte *)buffers->samples);
    }
    spi_wipe((uint8_t *)buffers, sizeof(*buffers));
    free(buffers);
    if ((*env)->ExceptionCheck(env)) return NULL;
    (*env)->SetObjectArrayElement(env, response, 0, report_bytes);
    (*env)->SetObjectArrayElement(env, response, 1, result.progress.payload_bytes == SPI_READ_PROBE_BYTES ? full : empty);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return response;
}
#endif
