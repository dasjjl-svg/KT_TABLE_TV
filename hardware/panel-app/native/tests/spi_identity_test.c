#define SPI_IDENTITY_HOST_TEST 1
#include "../spi_identity.c"

static unsigned checks;
#define CHECK(value) do { checks++; if (!(value)) { fprintf(stderr, "FAILED line %d: %s\n", __LINE__, #value); exit(1); } } while (0)

struct fake {
    uint32_t regs[R_COUNT], clock;
    uint64_t time, ready_at;
    uint8_t ids[2][8], status[2], buffer[SPI_CHUNK_BYTES], commands[32809];
    unsigned prepares, owns, reads, writes, buffers, pauses, closes, started;
    unsigned fail_read, fail_write, fail_buffer, fail_own;
    unsigned restore_collision, completed_fault, timeout_command, clear_late;
    unsigned clock_failure_command, deadline_after_buffer, second_snapshot_mutation;
    unsigned backup, array_started, array_done, ids_done, statuses_done;
    unsigned read_probe, buffer_mode;
    uint32_t read_origin, read_count;
    unsigned corrupt_array, uniform_array, prefix_pattern, erased_tail;
    unsigned status_fault_at, id_fault_at, fault_at_command;
    int prepare_reason, ownership_reason, pause_reason, close_reason;
    int pending, no_more_writes, late_writes, barrier_type;
    int writes_at_fault, clocks_fail, readback_bad;
    uint32_t write_offsets[70000], write_values[70000], array_addresses[32770];
};

static int index_for(uint32_t offset) {
    int i;
    for (i = 0; i < R_COUNT; i++) if (spi_offsets[i] == offset) return i;
    return -1;
}

static void fake_init(struct fake *f) {
    static const uint8_t id[8] = {0xef, 0x40, 0x15, 0, 0, 0, 0, 0};
    memset(f, 0, sizeof(*f));
    memcpy(f->regs, spi_baseline, sizeof(f->regs));
    memcpy(f->ids[0], id, sizeof(id));
    memcpy(f->ids[1], id, sizeof(id));
    f->clock = SPI_CLOCK;
    f->time = 1000;
    for (size_t i = 0; i < sizeof(f->buffer); i++) f->buffer[i] = (uint8_t)(i * 7 + 0x5a);
}

static void mark_fault(struct fake *f) {
    f->no_more_writes = 1;
    f->writes_at_fault = (int)f->writes;
}

static int fake_prepare(void *opaque, uint64_t deadline) {
    struct fake *f = opaque;
    f->prepares++;
    CHECK(deadline == f->time + (f->read_probe ? SPI_READ_PROBE_MS : f->backup ? SPI_BACKUP_MS : SPI_TOTAL_MS));
    return f->prepare_reason;
}

static uint64_t fake_now(void *opaque) {
    struct fake *f = opaque;
    if (f->clocks_fail || (f->clock_failure_command && f->started >= f->clock_failure_command)) return UINT64_MAX;
    return f->time;
}

static int fake_clock(void *opaque, uint32_t *value) {
    struct fake *f = opaque;
    *value = f->clock;
    return SPI_OK;
}

static uint8_t flash_byte(uint32_t address) {
    return (uint8_t)((address * UINT32_C(33)) ^ (address >> 8) ^ (address >> 13) ^ UINT32_C(0x5a));
}

struct wire_frame {
    uint8_t bytes[4];
    unsigned command_length;
    uint32_t address, data_length;
};

static int decode_wire_frame(const uint32_t registers[R_COUNT], uint32_t operation, struct wire_frame *frame) {
    const uint32_t spec_command_one = 1U << 7, spec_address = 1U << 6;
    const uint32_t spec_data_read = 1U << 2, spec_start = 1U;
    uint32_t command = registers[R_CMD], config = registers[R_OP_CFG];
    uint32_t expected = spec_command_one | spec_data_read | spec_start;
    unsigned address_bytes = (config >> 4) & 7U;
    memset(frame, 0, sizeof(*frame));
    if (config & ~UINT32_C(0x70)) return 0;
    if (command == 3) {
        expected |= spec_address;
        if (address_bytes != 3 || registers[R_ADDRH] || registers[R_ADDRL] > 0xffffffU) return 0;
        frame->bytes[1] = (uint8_t)(registers[R_ADDRL] >> 16);
        frame->bytes[2] = (uint8_t)(registers[R_ADDRL] >> 8);
        frame->bytes[3] = (uint8_t)registers[R_ADDRL];
        frame->command_length = 4;
        frame->address = (uint32_t)frame->bytes[1] << 16 | (uint32_t)frame->bytes[2] << 8 | frame->bytes[3];
    } else {
        if (command != 5 && command != 0x9f) return 0;
        if (address_bytes || registers[R_ADDRH] || registers[R_ADDRL]) return 0;
        frame->command_length = 1;
    }
    if (operation != expected) return 0;
    if (!registers[R_DATA_NUM] || registers[R_DATA_NUM] > 0x3fff) return 0;
    frame->bytes[0] = (uint8_t)command;
    frame->data_length = registers[R_DATA_NUM];
    return 1;
}

static void fake_finish(struct fake *f) {
    unsigned command = f->started;
    f->pending = 0;
    f->regs[R_OP] = f->regs[R_CMD] == 3 ? 0xc4 : 0x84;
    if (f->backup || f->read_probe) {
        if (f->regs[R_CMD] == 3) {
            uint32_t address = f->read_origin;
            size_t i;
            f->array_done++;
            for (i = 0; i < f->read_count; i++) {
                uint32_t source = (f->uniform_array || f->buffer_mode == 4 ? 0 : address) + (uint32_t)i;
                if (f->buffer_mode == 1 || (f->buffer_mode == 2 && i >= 256)) continue;
                if (f->buffer_mode == 3) source %= 512;
                if (f->buffer_mode == 5) source %= 256;
                f->buffer[i] = flash_byte(source);
                if (f->erased_tail && address >= SPI_CHUNK_BYTES) f->buffer[i] = 0xff;
                if (f->prefix_pattern == 1) f->buffer[i] = 0;
                if (f->prefix_pattern == 2) f->buffer[i] = 0xff;
                if (f->prefix_pattern == 3) f->buffer[i] = spi_expected_id[i % 3];
                if (f->prefix_pattern == 4) f->buffer[i] = spi_expected_id[i % 8];
            }
            if (f->corrupt_array == f->array_done) f->buffer[123] ^= 1;
        } else if (f->regs[R_CMD] == 5) {
            f->statuses_done++;
            f->buffer[0] = f->status_fault_at == f->statuses_done ? f->status[1] : f->status[0];
        } else {
            f->ids_done++;
            memcpy(f->buffer, spi_expected_id, 8);
            if (f->id_fault_at == f->ids_done) f->buffer[1] ^= 1;
        }
    } else if (command == 1 || command == 4) f->buffer[0] = f->status[command == 4];
    else memcpy(f->buffer, f->ids[command - 2], 8);
    if (f->completed_fault && command == (f->fault_at_command ? f->fault_at_command : 1)) {
        if (f->completed_fault == 1) f->regs[R_CMD] = 0x99;
        if (f->completed_fault == 2) f->regs[R_INT] |= 2;
        if (f->completed_fault == 3) f->regs[R_OP_CTRL] |= 1;
        if (f->completed_fault == 4) f->clock ^= 1;
        mark_fault(f);
    }
}

static int fake_read(void *opaque, uint32_t offset, uint32_t *value) {
    struct fake *f = opaque;
    int index = index_for(offset);
    CHECK(index >= 0);
    f->reads++;
    if (f->fail_read == f->reads) { mark_fault(f); return SPI_ACCESS; }
    if (index == R_OP && f->pending && f->time >= f->ready_at
            && f->started != f->timeout_command) fake_finish(f);
    if (index == R_OP && f->pending && f->clear_late && f->time + 1 >= f->ready_at) {
        f->time++;
        fake_finish(f);
        mark_fault(f);
    }
    if (f->readback_bad && f->writes && index == R_CFG) {
        f->regs[R_CFG] ^= 0x100;
        f->readback_bad = 0;
        mark_fault(f);
    }
    *value = f->regs[index];
    return SPI_OK;
}

static int fake_write(void *opaque, uint32_t offset, uint32_t value) {
    struct fake *f = opaque;
    int index = index_for(offset);
    CHECK(index >= 0 && (f->read_probe ? spi_write_allowed_read_probe(offset, value)
            : f->backup ? spi_write_allowed_backup(offset, value) : spi_write_allowed(offset, value)));
    CHECK(f->writes < 70000);
    if (f->no_more_writes) f->late_writes++;
    f->write_offsets[f->writes] = offset;
    f->write_values[f->writes++] = value;
    if (f->fail_write == f->writes) { mark_fault(f); return SPI_ACCESS; }
    if (index == R_OP) {
        struct wire_frame frame;
        CHECK(decode_wire_frame(f->regs, value, &frame));
        CHECK(f->started < (f->read_probe ? 18U : f->backup ? 32809U : 4U));
        if (f->regs[R_CMD] == 3) {
            CHECK(frame.bytes[0] == 3 && frame.command_length == 4);
            if (f->read_probe) {
                CHECK((frame.data_length == 256 && spi_read_probe_address(frame.address))
                        || (frame.data_length == SPI_CHUNK_BYTES && frame.address == 0));
            } else {
                CHECK(f->backup && frame.data_length == 256 && frame.address % 256 == 0);
                CHECK((uint64_t)frame.address + frame.data_length <= SPI_ARRAY_BYTES);
            }
            CHECK(f->array_started < 32770);
            f->read_origin = frame.address;
            f->read_count = frame.data_length;
            f->array_addresses[f->array_started++] = frame.address;
        } else {
            CHECK(value == 0x85 && (f->regs[R_CMD] == 5 || f->regs[R_CMD] == 0x9f));
            CHECK(f->regs[R_DATA_NUM] == (f->regs[R_CMD] == 5 ? 1U : 8U));
            CHECK(f->regs[R_ADDRH] == 0 && f->regs[R_ADDRL] == 0 && f->regs[R_OP_CFG] == 0);
        }
        CHECK(f->regs[R_OP_PARA] == 0);
        CHECK(f->regs[R_CFG] == 1 && f->regs[R_GLOBAL] == 0xc0);
        CHECK(f->barrier_type == 0);
        f->commands[f->started++] = (uint8_t)f->regs[R_CMD];
        f->pending = 1;
        f->ready_at = f->time + (f->clear_late ? SPI_OPERATION_MS : 2);
    }
    f->regs[index] = value;
    if (f->restore_collision && f->started == (f->read_probe ? 18U : f->backup ? 32809U : 4U) && index == R_CMD && value == 3) {
        f->regs[R_TIMING] ^= 1;
        mark_fault(f);
    }
    return SPI_OK;
}

static int fake_buffer(void *opaque, uint8_t *output, size_t count) {
    struct fake *f = opaque;
    CHECK(spi_buffer_allowed(f->backup, f->read_probe, count));
    CHECK(!f->pending && f->regs[R_OP] == (count >= 256 ? 0xc4U : 0x84U));
    f->buffers++;
    if (f->fail_buffer == f->buffers) { mark_fault(f); return SPI_ACCESS; }
    memcpy(output, f->buffer, count);
    if (f->deadline_after_buffer == f->buffers) {
        f->time += f->read_probe ? SPI_READ_PROBE_MS : f->backup ? SPI_BACKUP_MS : SPI_TOTAL_MS;
        mark_fault(f);
    }
    return SPI_OK;
}

static int fake_owner(void *opaque) {
    struct fake *f = opaque;
    f->owns++;
    if (f->fail_own == f->owns) { mark_fault(f); return SPI_OWNER; }
    return f->ownership_reason;
}

static int fake_pause(void *opaque, unsigned millis) {
    struct fake *f = opaque;
    CHECK(millis == 100 || millis == 1);
    f->pauses++;
    if (f->pause_reason) { mark_fault(f); return f->pause_reason; }
    f->time += millis;
    if (millis == 100 && f->second_snapshot_mutation) f->regs[R_TIMING] ^= 1;
    return SPI_OK;
}

static void fake_barrier(void *opaque, int full) {
    struct fake *f = opaque;
    CHECK(full == 0 || full == 1);
    f->barrier_type = full;
}

static int fake_close(void *opaque) {
    struct fake *f = opaque;
    f->closes++;
    return f->close_reason;
}

static struct spi_result fake_run(struct fake *f) {
    struct spi_result result;
    struct spi_io io = {f, fake_prepare, fake_clock, fake_read, fake_write, fake_buffer,
        fake_owner, fake_now, fake_pause, fake_barrier, fake_close};
    spi_run_identity(&io, &result);
    CHECK(f->closes == 1);
    CHECK(f->late_writes == 0);
    CHECK(result.commands_started <= 4);
    CHECK(result.incomplete == (result.changed && !result.restored));
    return result;
}

static void check_restored(const struct fake *f) {
    size_t i;
    for (i = 0; i < sizeof(spi_restore_order) / sizeof(spi_restore_order[0]); i++) {
        int index = spi_restore_order[i];
        CHECK(f->regs[index] == spi_baseline[index]);
    }
    CHECK(f->regs[R_TIMING] == spi_baseline[R_TIMING]);
    CHECK(f->regs[R_DMA_LEN] == spi_baseline[R_DMA_LEN]);
    CHECK(f->regs[R_OP_CTRL] == spi_baseline[R_OP_CTRL]);
    CHECK(f->regs[R_INT_EN] == 0);
}

static struct spi_backup_result fake_backup_run(struct fake *f, uint8_t *payload) {
    struct spi_backup_result result;
    struct spi_io io = {f, fake_prepare, fake_clock, fake_read, fake_write, fake_buffer,
        fake_owner, fake_now, fake_pause, fake_barrier, fake_close};
    uint8_t any = 0;
    uint32_t i;
    f->backup = 1;
    spi_run_backup(&io, &result, payload, SPI_ARRAY_BYTES);
    CHECK(f->closes == 1 && f->late_writes == 0);
    CHECK(result.common.commands_started <= 32809 && result.array_commands <= 32770);
    CHECK(result.common.incomplete == (result.common.changed && !result.common.restored));
    if (result.common.reason != SPI_OK) {
        CHECK(result.payload_bytes == 0);
        if (payload) {
            for (i = 0; i < SPI_ARRAY_BYTES; i++) any |= payload[i];
            CHECK(any == 0);
        }
    }
    return result;
}

static void backup_tests(void) {
    struct fake f;
    struct spi_backup_result result;
    uint8_t *payload = malloc(SPI_ARRAY_BYTES);
    char output[SPI_OUTPUT_MAX];
    uint32_t i;
    unsigned golden_reads, golden_writes, golden_owns;
    static const unsigned phases[] = {5, 6, 7, 1030, 16406, 16407, 32806, 32807, 32808, 32809};
    static const unsigned array_boundaries[] = {2, 3, 1026, 16386, 16387, 32770};
    CHECK(payload != NULL);
    fake_init(&f);
    result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_OK && result.common.restored && !result.common.incomplete);
    CHECK(result.payload_bytes == SPI_ARRAY_BYTES && result.prefix_verified && result.diverse);
    CHECK(result.passes_completed == 2 && result.status_checks == 35 && result.id_checks == 4);
    CHECK(result.common.commands_started == 32809 && result.array_commands == 32770);
    CHECK(result.array_bytes_read == 8389120);
    CHECK(f.array_addresses[0] == 0 && f.array_addresses[1] == 0);
    for (i = 0; i < 16384; i++) {
        CHECK(f.array_addresses[i + 2] == i * 256);
        CHECK(f.array_addresses[i + 16386] == i * 256);
    }
    CHECK(f.array_addresses[16385] == 0x3fff00 && f.array_addresses[32769] == 0x3fff00);
    for (i = 0; i < SPI_ARRAY_BYTES; i++) if (payload[i] != flash_byte(i)) break;
    CHECK(i == SPI_ARRAY_BYTES);
    check_restored(&f);
    CHECK(f.regs[R_OP] == 0x84);
    spi_format_backup(&result, output);
    CHECK(strlen(output) < SPI_OUTPUT_MAX);
    CHECK(strstr(output, "status=ok\n") && strstr(output, "payload_bytes=4194304\n"));
    CHECK(strstr(output, "flash_array_read=verified_two_pass\n"));
    CHECK(strstr(output, "version=2\n") && strstr(output, "read_chunk_bytes=256\n"));
    CHECK(strstr(output, "array_commands=32770\n") && strstr(output, "status_checks=35\n"));
    CHECK(strstr(output, "id_checks=4\n") && strstr(output, "address_diversity=1\n"));
    {
        unsigned lines = 0;
        for (i = 0; output[i]; i++) {
            CHECK(output[i] == '\n' || (output[i] >= 32 && output[i] <= 126));
            if (output[i] == '\n') lines++;
        }
        CHECK(lines == 25);
    }
    golden_reads = f.reads; golden_writes = f.writes; golden_owns = f.owns;
    for (i = 0; i < f.writes; i++) {
        uint32_t offset = f.write_offsets[i], value = f.write_values[i];
        CHECK(spi_write_allowed_backup(offset, value));
        if (offset == 0x24) CHECK(value == 3 || value == 5 || value == 0x9f);
        if (offset == 0x3c) CHECK(value == 0x85 || value == 0xc5);
    }

    fake_init(&f); f.buffer_mode = 2; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_OK && result.payload_bytes == SPI_ARRAY_BYTES);
    CHECK(result.common.commands_started == 32809 && result.array_bytes_read == 8389120);
    for (i = 0; i < SPI_ARRAY_BYTES; i++) if (payload[i] != flash_byte(i)) break;
    CHECK(i == SPI_ARRAY_BYTES);
    check_restored(&f);

    fake_init(&f); f.buffer_mode = 1; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason != SPI_OK && result.common.restored);
    CHECK(result.payload_bytes == 0);

    fake_init(&f); f.erased_tail = 1; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_OK && result.diverse && result.payload_bytes == SPI_ARRAY_BYTES);
    CHECK(payload[4096] == 0xff && payload[SPI_ARRAY_BYTES - 1] == 0xff);
    for (i = 1; i <= 4; i++) {
        fake_init(&f); f.prefix_pattern = i; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_PREFIX_INVALID && result.common.restored && f.array_started == 2);
        CHECK(!result.prefix_verified);
        fake_init(&f); f.id_fault_at = i; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_ID_MISMATCH && result.common.restored);
        CHECK(result.id_checks == i);
    }
    fake_init(&f); f.uniform_array = 1; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_ARRAY_REPETITION && result.common.restored);
    CHECK(result.passes_completed == 1 && result.array_commands == 16386 && !result.diverse);
    for (i = 0; i < sizeof(array_boundaries) / sizeof(array_boundaries[0]); i++) {
        fake_init(&f); f.corrupt_array = array_boundaries[i]; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_ARRAY_MISMATCH && result.common.restored);
        CHECK(!result.common.incomplete);
    }
    for (i = 1; i <= 35; i += 17) {
        fake_init(&f); f.status_fault_at = i; f.status[1] = 1; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_STATUS_BUSY && result.common.restored && result.status_checks == i);
    }
    fake_init(&f); f.status_fault_at = 3; f.status[1] = 4; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_STATUS_CHANGED && result.common.restored && result.status_checks == 3);

    for (i = 0; i < sizeof(phases) / sizeof(phases[0]); i++) {
        unsigned phase = phases[i];
        fake_init(&f); f.timeout_command = phase; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_TIMEOUT && result.common.incomplete && f.started == phase);
        CHECK(f.write_offsets[f.writes - 1] == 0x3c);
        fake_init(&f); f.completed_fault = 1; f.fault_at_command = phase; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_COLLISION && result.common.incomplete && f.started == phase);
        CHECK(f.write_offsets[f.writes - 1] == 0x3c);
        fake_init(&f); f.deadline_after_buffer = phase; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_TIMEOUT && result.common.incomplete && f.started == phase);
        fake_init(&f); f.fail_buffer = phase; result = fake_backup_run(&f, payload);
        CHECK(result.common.reason == SPI_ACCESS && result.common.incomplete && f.started == phase);
    }
    for (i = 0; i < 3; i++) {
        fake_init(&f); f.fail_read = i == 0 ? 1 : i == 1 ? golden_reads / 2 : golden_reads;
        result = fake_backup_run(&f, payload);
        CHECK(result.common.reason != SPI_OK && !result.common.restored);
        fake_init(&f); f.fail_write = i == 0 ? 1 : i == 1 ? golden_writes / 2 : golden_writes;
        result = fake_backup_run(&f, payload);
        CHECK(result.common.reason != SPI_OK && result.common.incomplete);
        fake_init(&f); f.fail_own = i == 0 ? 1 : i == 1 ? golden_owns / 2 : golden_owns;
        result = fake_backup_run(&f, payload);
        CHECK(result.common.reason != SPI_OK && !result.common.restored);
    }
    fake_init(&f); f.restore_collision = 1; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_RESTORE_FAILED && result.common.incomplete && f.started == 32809);
    fake_init(&f); f.close_reason = SPI_CLOSE_FAILED; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_CLOSE_FAILED && result.common.restored);
    fake_init(&f); f.clock_failure_command = 16407; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_TIMEOUT && result.common.incomplete);
    fake_init(&f); f.clocks_fail = 1; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_TIMEOUT && !f.prepares && !f.writes);
    fake_init(&f); f.time = UINT64_MAX - SPI_BACKUP_MS + 1; result = fake_backup_run(&f, payload);
    CHECK(result.common.reason == SPI_TIMEOUT && !f.prepares && !f.writes);
    fake_init(&f); result = fake_backup_run(&f, NULL);
    CHECK(result.common.reason == SPI_MEMORY && !f.prepares && !f.writes);
    spi_format_backup(&result, output);
    CHECK(strstr(output, "payload_bytes=0\n") && strstr(output, "flash_array_read=not_performed\n"));

    {
        struct spi_result common;
        struct spi_run run;
        struct spi_io io = {&f, fake_prepare, fake_clock, fake_read, fake_write, fake_buffer,
            fake_owner, fake_now, fake_pause, fake_barrier, fake_close};
        static const uint32_t bad_addresses[] = {1, 255, 0x3fff01, 0x400000, UINT32_MAX};
        fake_init(&f);
        memset(&common, 0, sizeof(common)); memset(&run, 0, sizeof(run));
        run.io = &io; run.result = &common; run.array_enabled = 1;
        for (i = 0; i < sizeof(bad_addresses) / sizeof(bad_addresses[0]); i++) {
            CHECK(spi_transfer(&run, 3, bad_addresses[i], payload, 256) == SPI_ACCESS);
        }
        CHECK(spi_transfer(&run, 3, 0, payload, 255) == SPI_ACCESS);
        CHECK(spi_transfer(&run, 3, 0, payload, 257) == SPI_ACCESS);
        CHECK(spi_transfer(&run, 3, 0, payload, 4096) == SPI_ACCESS);
        run.array_enabled = 0;
        CHECK(spi_transfer(&run, 3, 0, payload, 256) == SPI_ACCESS);
        CHECK(!f.writes && !f.reads);
    }
    for (i = 0; i < 256; i++) {
        CHECK(spi_write_allowed_backup(0x24, i) == (i == 3 || i == 5 || i == 0x9f));
        CHECK(spi_write_allowed_backup(0x3c, i) == (i == 0x85 || i == 0xc5));
        CHECK(!spi_write_allowed_backup(0x20, i) && !spi_write_allowed_backup(0x68, i));
    }
    CHECK(spi_write_allowed_backup(0x2c, 0x3fff00));
    CHECK(!spi_write_allowed_backup(0x2c, 0x400000) && !spi_write_allowed_backup(0x2c, 1));
    CHECK(!spi_write_allowed_backup(0x2c, 0x3fff01) && !spi_write_allowed_backup(0x2c, UINT32_MAX));
    CHECK(!spi_write_allowed_backup(0x38, 257) && !spi_write_allowed_backup(0x38, 255));
    CHECK(!spi_write_allowed_backup(0x38, 4096));
    CHECK(spi_buffer_allowed(1, 0, 256) && !spi_buffer_allowed(1, 0, 4096));
    CHECK(!spi_buffer_allowed(1, 0, 255) && !spi_buffer_allowed(1, 0, 257));
    CHECK(spi_buffer_allowed(0, 1, 256) && spi_buffer_allowed(0, 1, 4096));
    CHECK(!spi_buffer_allowed(0, 0, 256) && !spi_buffer_allowed(0, 0, 4096));
    free(payload);
    printf("SPI backup fake-MMIO: two 4MiB passes, 32770 bounded 256B array reads, prefix/diversity and representative failures verified.\n");
}

static struct spi_read_probe_result fake_read_probe_run(struct fake *f, struct spi_read_probe_buffers *buffers) {
    struct spi_read_probe_result result;
    struct spi_io io = {f, fake_prepare, fake_clock, fake_read, fake_write, fake_buffer,
        fake_owner, fake_now, fake_pause, fake_barrier, fake_close};
    f->read_probe = 1;
    spi_run_read_probe(&io, &result, buffers);
    CHECK(f->closes == 1 && !f->late_writes);
    CHECK(result.progress.common.commands_started <= 18 && result.progress.array_commands <= 10);
    CHECK(result.progress.common.incomplete == (result.progress.common.changed && !result.progress.common.restored));
    if (result.progress.common.reason != SPI_OK) {
        uint8_t combined = 0;
        size_t i;
        CHECK(result.progress.payload_bytes == 0 && !result.long_present);
        if (buffers) {
            const uint8_t *data = (const uint8_t *)buffers;
            for (i = 0; i < sizeof(*buffers); i++) combined |= data[i];
            CHECK(combined == 0);
        }
    }
    return result;
}

static void read_probe_tests(void) {
    struct fake f;
    struct spi_read_probe_buffers buffers;
    struct spi_read_probe_result result;
    char output[SPI_OUTPUT_MAX];
    size_t maximum_report = 0;
    unsigned i, j, golden_reads, golden_writes, golden_owns;
    fake_init(&f);
    result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_OK && result.progress.payload_bytes == 2048);
    CHECK(result.progress.common.commands_started == 18 && result.progress.array_commands == 10);
    CHECK(result.progress.array_bytes_read == 10240 && result.progress.status_checks == 4 && result.progress.id_checks == 4);
    CHECK(result.sample_count == 8 && result.sample_bytes == 2048);
    CHECK(result.short_repeat == 1 && result.long_repeat == 1);
    CHECK(result.overlap[0] == 1 && result.overlap[1] == 1 && result.overlap[2] == 1);
    CHECK(!result.period_256 && !result.period_512);
    CHECK(result.long_present && result.progress.common.restored && !result.progress.common.incomplete);
    for (i = 0; i < 8; i++) {
        CHECK(f.array_addresses[i] == spi_read_probe_addresses[i]);
        for (j = 0; j < 256; j++) CHECK(buffers.samples[i * 256 + j] == flash_byte(spi_read_probe_addresses[i] + j));
    }
    CHECK(f.array_addresses[8] == 0 && f.array_addresses[9] == 0);
    CHECK(!memcmp(result.long_head, buffers.long_first, 32));
    CHECK(!memcmp(result.long_mid, buffers.long_first + 0x800, 32));
    CHECK(!memcmp(result.long_tail, buffers.long_first + 0xfe0, 32));
    check_restored(&f);
    spi_format_read_probe(&result, output);
    maximum_report = strlen(output);
    CHECK(maximum_report < SPI_OUTPUT_MAX && strstr(output, "address_space_mode=unverified\n"));
    CHECK(strstr(output, "status=ok\nreason=read_probe_complete\n"));
    CHECK(strstr(output, "flash_array_read=sampled_addresses\n") && strstr(output, "sample_bytes=2048\n"));
    j = 0; for (i = 0; output[i]; i++) if (output[i] == '\n') j++;
    CHECK(j == 33);
    golden_reads = f.reads; golden_writes = f.writes; golden_owns = f.owns;

    for (i = 1; i <= 5; i++) {
        fake_init(&f); f.buffer_mode = i; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_OK && result.progress.payload_bytes == 2048);
        CHECK(result.short_repeat == 1 && result.long_repeat == 1);
        if (i == 1) CHECK(!memcmp(buffers.samples, buffers.samples + 256, 256));
        if (i == 2) CHECK(result.overlap[0] == 1 && result.overlap[1] == 0 && result.overlap[2] == 0);
        if (i == 3) CHECK(result.period_256 == 0 && result.period_512 == 1);
        if (i == 4) CHECK(result.overlap[0] == 1 && result.overlap[1] == 0 && result.overlap[2] == 0);
        if (i == 5) CHECK(result.period_256 == 1 && result.period_512 == 1);
        spi_format_read_probe(&result, output);
        if (strlen(output) > maximum_report) maximum_report = strlen(output);
        CHECK(strlen(output) < SPI_OUTPUT_MAX);
    }
    for (i = 1; i <= 4; i++) {
        fake_init(&f); f.prefix_pattern = i; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_OK && result.progress.payload_bytes == 2048);
    }
    for (i = 8; i <= 10; i++) {
        fake_init(&f); f.corrupt_array = i; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_OK && result.progress.payload_bytes == 2048);
        if (i == 8) CHECK(result.short_repeat == 0);
        else CHECK(result.long_repeat == 0);
    }
    fake_init(&f); f.corrupt_array = 2; result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_OK && result.overlap[1] == 0);
    for (i = 1; i <= 18; i++) {
        fake_init(&f); f.timeout_command = i; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_TIMEOUT && result.progress.common.incomplete && f.started == i);
        CHECK(f.write_offsets[f.writes - 1] == 0x3c);
        fake_init(&f); f.fail_buffer = i; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_ACCESS && result.progress.common.incomplete && f.started == i);
        fake_init(&f); f.completed_fault = 1; f.fault_at_command = i; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_COLLISION && result.progress.common.incomplete && f.started == i);
    }
    for (i = 1; i <= 4; i++) {
        fake_init(&f); f.status_fault_at = i; f.status[1] = 2; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_STATUS_BUSY && result.progress.common.restored);
        fake_init(&f); f.id_fault_at = i; result = fake_read_probe_run(&f, &buffers);
        CHECK(result.progress.common.reason == SPI_ID_MISMATCH && result.progress.common.restored);
    }
    for (i = 0; i < 3; i++) {
        fake_init(&f); f.fail_read = i == 0 ? 1 : i == 1 ? golden_reads / 2 : golden_reads;
        result = fake_read_probe_run(&f, &buffers); CHECK(result.progress.common.reason != SPI_OK);
        fake_init(&f); f.fail_write = i == 0 ? 1 : i == 1 ? golden_writes / 2 : golden_writes;
        result = fake_read_probe_run(&f, &buffers); CHECK(result.progress.common.reason != SPI_OK);
        fake_init(&f); f.fail_own = i == 0 ? 1 : i == 1 ? golden_owns / 2 : golden_owns;
        result = fake_read_probe_run(&f, &buffers); CHECK(result.progress.common.reason != SPI_OK);
    }
    fake_init(&f); f.restore_collision = 1; result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_RESTORE_FAILED && result.progress.common.incomplete);
    fake_init(&f); f.deadline_after_buffer = 10; result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_TIMEOUT && result.progress.common.incomplete);
    fake_init(&f); f.clock_failure_command = 6; result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_TIMEOUT && result.progress.common.incomplete);
    fake_init(&f); f.close_reason = SPI_CLOSE_FAILED; result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_CLOSE_FAILED && result.progress.common.restored);
    spi_format_read_probe(&result, output);
    CHECK(strstr(output, "long_head32_hex=unknown\n") && strstr(output, "payload_bytes=0\n"));
    if (strlen(output) > maximum_report) maximum_report = strlen(output);
    fake_init(&f); f.clocks_fail = 1; result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_TIMEOUT && !f.prepares && !f.writes);
    fake_init(&f); f.time = UINT64_MAX - SPI_READ_PROBE_MS + 1; result = fake_read_probe_run(&f, &buffers);
    CHECK(result.progress.common.reason == SPI_TIMEOUT && !f.prepares && !f.writes);
    fake_init(&f); result = fake_read_probe_run(&f, NULL);
    CHECK(result.progress.common.reason == SPI_MEMORY && !f.prepares && !f.writes);
    spi_format_read_probe(&result, output);
    CHECK(strstr(output, "short_repeat_equal=unknown\n") && strstr(output, "long_period_512=unknown\n"));
    if (strlen(output) > maximum_report) maximum_report = strlen(output);

    {
        struct wire_frame frame;
        uint32_t registers[R_COUNT];
        memcpy(registers, spi_baseline, sizeof(registers));
        registers[R_CMD] = 3; registers[R_OP_CFG] = 3U << 4;
        registers[R_ADDRH] = 0; registers[R_ADDRL] = 0x123456; registers[R_DATA_NUM] = 256;
        CHECK(decode_wire_frame(registers, 0xc5, &frame));
        CHECK(frame.command_length == 4 && !memcmp(frame.bytes, "\x03\x12\x34\x56", 4));
        CHECK(frame.address == 0x123456 && frame.data_length == 256);
        CHECK(!decode_wire_frame(registers, 0x85, &frame));
        CHECK(!decode_wire_frame(registers, 0x185, &frame));
        CHECK(!decode_wire_frame(registers, 0x1c5, &frame));
        CHECK(!decode_wire_frame(registers, 0xc7, &frame));
        registers[R_OP_CFG] = 0; CHECK(!decode_wire_frame(registers, 0xc5, &frame));
        registers[R_OP_CFG] = 4U << 4; CHECK(!decode_wire_frame(registers, 0xc5, &frame));
        registers[R_OP_CFG] = (3U << 4) | 1; CHECK(!decode_wire_frame(registers, 0xc5, &frame));
    }
    {
        struct spi_result common;
        struct spi_run run;
        struct spi_io io = {&f, fake_prepare, fake_clock, fake_read, fake_write, fake_buffer,
            fake_owner, fake_now, fake_pause, fake_barrier, fake_close};
        static const uint32_t bad_addresses[] = {1, 255, 0x300, 0x100000, 0x400000, UINT32_MAX};
        fake_init(&f);
        memset(&common, 0, sizeof(common)); memset(&run, 0, sizeof(run));
        run.io = &io; run.result = &common; run.read_probe_enabled = 1;
        for (i = 0; i < sizeof(bad_addresses) / sizeof(bad_addresses[0]); i++) {
            CHECK(spi_transfer(&run, 3, bad_addresses[i], buffers.samples, 256) == SPI_ACCESS);
        }
        CHECK(spi_transfer(&run, 3, 0x1000, buffers.long_first, 4096) == SPI_ACCESS);
        CHECK(spi_transfer(&run, 3, 0, buffers.samples, 255) == SPI_ACCESS);
        CHECK(spi_transfer(&run, 3, 0, buffers.samples, 257) == SPI_ACCESS);
        run.read_probe_enabled = 0; run.array_enabled = 0;
        CHECK(spi_transfer(&run, 3, 0, buffers.samples, 256) == SPI_ACCESS);
        CHECK(!f.writes && !f.reads);
    }
    for (i = 0; i < 8; i++) CHECK(spi_write_allowed_read_probe(0x2c, spi_read_probe_addresses[i]));
    CHECK(!spi_write_allowed_read_probe(0x2c, 0x300) && !spi_write_allowed_read_probe(0x2c, 0x400000));
    CHECK(spi_write_allowed_backup(0x2c, 0x100) && !spi_write_allowed(0x2c, 0x100));
    CHECK(maximum_report < 1500);
    printf("SPI read probe fake-MMIO: independent wire bits; stale/partial/512-alias/missing-address models; report maximum %zu bytes.\n", maximum_report);
}

int main(void) {
    struct fake f, golden;
    struct spi_result result;
    char output[SPI_OUTPUT_MAX];
    unsigned i, golden_reads, golden_writes, golden_owns;

    fake_init(&golden);
    result = fake_run(&golden);
    CHECK(result.reason == SPI_OK && result.restored && !result.incomplete);
    CHECK(result.have_before && result.have_id1 && result.have_id2 && result.have_after);
    CHECK(result.commands_started == 4 && golden.started == 4);
    CHECK(!memcmp(golden.commands, "\x05\x9f\x9f\x05", 4));
    CHECK(golden.regs[R_OP] == 0x84 && golden.regs[R_INT] == 1);
    check_restored(&golden);
    for (i = 0; i < golden.writes; i++) {
        if (golden.write_offsets[i] == 0x3c) CHECK(golden.write_values[i] == 0x85);
        CHECK(golden.write_offsets[i] != 0x20 && golden.write_offsets[i] != 0x18
                && golden.write_offsets[i] != 0x1c && golden.write_offsets[i] != 0x68
                && golden.write_offsets[i] != 0x40 && golden.write_offsets[i] != 0x08);
    }
    spi_format(&result, output);
    CHECK(strlen(output) < SPI_OUTPUT_MAX);
    CHECK(strstr(output, "status=ok\n") && strstr(output, "config_restored=1\n"));
    CHECK(strstr(output, "interrupt_buffer_preserved=not_guaranteed\n"));
    CHECK(strstr(output, "op_flags_left=not_restored\n"));
    CHECK(strstr(output, "ownership=not_established\n"));
    CHECK(strstr(output, "flash_array_read=not_performed\n"));
    for (i = 0; output[i]; i++) CHECK(output[i] == '\n' || (output[i] >= 32 && output[i] <= 126));
    golden_reads = golden.reads; golden_writes = golden.writes; golden_owns = golden.owns;

    for (i = 0; i < R_COUNT; i++) {
        fake_init(&f); f.regs[i] ^= 1;
        result = fake_run(&f);
        CHECK(result.reason != SPI_OK && !result.changed && !f.writes && !f.started);
    }
    fake_init(&f); f.clock = 0x80; result = fake_run(&f);
    CHECK(result.reason == SPI_PREFLIGHT && !f.writes);
    fake_init(&f); f.clock = 0x91; result = fake_run(&f);
    CHECK(result.reason == SPI_PREFLIGHT && !f.writes);
    fake_init(&f); f.second_snapshot_mutation = 1; result = fake_run(&f);
    CHECK(result.reason == SPI_COLLISION && !f.writes && f.pauses == 1);

    for (i = SPI_ROOT; i <= SPI_IRQ; i++) {
        fake_init(&f); f.prepare_reason = (int)i; result = fake_run(&f);
        CHECK(result.reason == (int)i && !f.writes && !f.started);
    }
    fake_init(&f); f.ownership_reason = SPI_OWNER; result = fake_run(&f);
    CHECK(result.reason == SPI_OWNER && !f.writes);
    fake_init(&f); f.ownership_reason = SPI_IRQ; result = fake_run(&f);
    CHECK(result.reason == SPI_IRQ && !f.writes);

    for (i = 1; i <= 3; i++) {
        fake_init(&f); f.status[0] = (uint8_t)i; result = fake_run(&f);
        CHECK(result.reason == SPI_STATUS_BUSY && f.started == 1 && result.restored);
        check_restored(&f);
    }
    for (i = 0; i < 3; i++) {
        fake_init(&f); f.ids[0][0] = i == 0 ? 0 : i == 1 ? 0xff : 0x7f;
        result = fake_run(&f);
        CHECK(result.reason == SPI_ID_INVALID && f.started == 2 && result.restored);
        check_restored(&f);
    }
    fake_init(&f); f.ids[1][0] = 0xff; result = fake_run(&f);
    CHECK(result.reason == SPI_ID_INVALID && f.started == 3 && result.restored);
    fake_init(&f); f.ids[1][2] ^= 1; result = fake_run(&f);
    CHECK(result.reason == SPI_ID_MISMATCH && f.started == 3 && result.restored);
    fake_init(&f); f.status[1] = 1; result = fake_run(&f);
    CHECK(result.reason == SPI_STATUS_BUSY && f.started == 4 && result.restored);
    fake_init(&f); f.status[1] = 4; result = fake_run(&f);
    CHECK(result.reason == SPI_STATUS_CHANGED && f.started == 4 && result.restored);

    for (i = 1; i <= 4; i++) {
        fake_init(&f); f.timeout_command = i; result = fake_run(&f);
        CHECK(result.reason == SPI_TIMEOUT && result.incomplete && f.started == i);
        CHECK(f.write_offsets[f.writes - 1] == 0x3c);
        fake_init(&f); f.clock_failure_command = i; result = fake_run(&f);
        CHECK(result.reason == SPI_TIMEOUT && result.incomplete && f.started == i);
        CHECK(f.write_offsets[f.writes - 1] == 0x3c);
    }
    fake_init(&f); f.clear_late = 1; result = fake_run(&f);
    CHECK(result.reason == SPI_TIMEOUT && result.incomplete && f.started == 1);
    CHECK(f.regs[R_OP] == 0x84 && f.write_offsets[f.writes - 1] == 0x3c);
    for (i = 1; i <= 4; i++) {
        fake_init(&f); f.completed_fault = i; result = fake_run(&f);
        CHECK(result.reason != SPI_OK && result.incomplete && f.started == 1);
        CHECK(f.write_offsets[f.writes - 1] == 0x3c);
    }
    fake_init(&f); f.readback_bad = 1; result = fake_run(&f);
    CHECK(result.reason == SPI_COLLISION && result.incomplete && f.started == 0 && f.writes == 1);

    for (i = 1; i <= golden_reads; i++) {
        fake_init(&f); f.fail_read = i; result = fake_run(&f);
        CHECK(result.reason != SPI_OK && !result.restored);
    }
    for (i = 1; i <= golden_writes; i++) {
        fake_init(&f); f.fail_write = i; result = fake_run(&f);
        CHECK(result.reason != SPI_OK && !result.restored && result.incomplete);
    }
    for (i = 1; i <= 4; i++) {
        fake_init(&f); f.fail_buffer = i; result = fake_run(&f);
        CHECK(result.reason == SPI_ACCESS && result.incomplete && f.started == i);
        fake_init(&f); f.deadline_after_buffer = i; result = fake_run(&f);
        CHECK(result.reason == SPI_TIMEOUT && result.incomplete && f.started == i);
    }
    for (i = 1; i <= golden_owns; i++) {
        fake_init(&f); f.fail_own = i; result = fake_run(&f);
        CHECK(result.reason != SPI_OK && !result.restored);
    }
    fake_init(&f); f.restore_collision = 1; result = fake_run(&f);
    CHECK(result.reason == SPI_RESTORE_FAILED && result.incomplete && f.started == 4);
    CHECK(f.write_offsets[f.writes - 1] == 0x24 && f.write_values[f.writes - 1] == 3);
    fake_init(&f); f.pause_reason = SPI_INTERRUPTED; result = fake_run(&f);
    CHECK(result.reason == SPI_INTERRUPTED && !f.writes);
    fake_init(&f); f.clocks_fail = 1; result = fake_run(&f);
    CHECK(result.reason == SPI_TIMEOUT && !f.prepares && !f.writes);
    fake_init(&f); f.time = UINT64_MAX - SPI_TOTAL_MS + 1; result = fake_run(&f);
    CHECK(result.reason == SPI_TIMEOUT && !f.prepares && !f.writes);
    fake_init(&f); f.time = UINT64_MAX - SPI_TOTAL_MS; result = fake_run(&f);
    CHECK(result.reason == SPI_OK && result.restored);
    fake_init(&f); f.close_reason = SPI_CLOSE_FAILED; result = fake_run(&f);
    CHECK(result.reason == SPI_CLOSE_FAILED && result.restored);
    fake_init(&f); f.prepare_reason = SPI_ROOT; result = fake_run(&f);
    spi_format(&result, output);
    CHECK(strstr(output, "id1=unknown\nid2=unknown\nsr_before=unknown\nsr_after=unknown\n"));
    CHECK(strstr(output, "config_restored=not_changed\nrestoration_incomplete=0\n"));
    for (i = 0; i <= 255; i++) {
        CHECK(spi_write_allowed(0x24, i) == (i == 3 || i == 5 || i == 0x9f));
        CHECK(spi_write_allowed(0x3c, i) == (i == 0x85));
        CHECK(!spi_write_allowed(0x20, i) && !spi_write_allowed(0x18, i));
        CHECK(!spi_write_allowed(0x1c, i) && !spi_write_allowed(0x68, i));
        CHECK(!spi_write_allowed(0x40, i) && !spi_write_allowed(0x08, i));
    }
    CHECK(spi_claim_once() && !spi_claim_once());
    backup_tests();
    read_probe_tests();
    printf("SPI identity fake-MMIO: %u checks passed; %u individual read and %u write fault sites covered.\n",
            checks, golden_reads, golden_writes);
    return 0;
}
