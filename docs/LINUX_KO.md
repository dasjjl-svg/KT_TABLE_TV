# Linux 설치

Ubuntu 24.04 LTS 계열 **x86_64 PC**에서 패널 서버·Android 앱 빌드·원격 업데이트를 실행하는 경로입니다. Windows의 WSL2 Ubuntu에서도 이 절차를 사용하되 [Windows 준비](WINDOWS_KO.md)를 먼저 완료합니다. Linux ARM64, Alpine/musl과 다른 CPU용 Android 도구는 이번 배포 대상에 포함하지 않습니다.

## 1. 준비

일반 사용자 계정의 Linux 파일시스템에 설치합니다. 전체 설치 도우미를 sudo로 실행하지 않습니다. 다음 명령은 Ubuntu의 빌드 도구를 설치하는 단계입니다.

```sh
sudo apt update
sudo apt install git ca-certificates curl python3 openjdk-17-jdk clang libclang-rt-dev openssl unzip zip
```

[Node.js 공식 다운로드](https://nodejs.org/en/download)에서 Linux x64용 **22.12 이상 지원 중인 LTS**를 설치합니다. 배포판의 기본 `nodejs`가 요구 버전보다 오래된 경우 그대로 사용하지 않습니다. Node는 `npm`과 함께 같은 사용자 환경의 PATH에 있어야 합니다.

```sh
uname -m
node --version
npm --version
python3 --version
javac -version
clang --version
```

Python 3.10 이상, JDK 17 이상을 사용합니다. Home Assistant는 기존 서버를 연결하거나 [HA 공식 Linux 설치 안내](https://www.home-assistant.io/installation/linux/)의 HA OS 가상 머신 또는 Container 구성을 별도로 준비합니다. 이 설치 도우미는 HA·Docker·가상 머신을 자동 설치하지 않습니다. 센서와 계정 연결은 [HA 연동 안내](HOME_ASSISTANT_KO.md)를 참고합니다.

## 2. 설정·빌드·실행

```sh
git clone https://github.com/dasjjl-svg/KT_TABLE_TV.git
cd KT_TABLE_TV
bash Setup.sh
bash Build-Android.sh
python3 hardware/panel_maintenance_server.py publish hardware/panel-app/HomePanel.apk
bash Start.sh
```

설정 질문에서 **서버 IPv4**는 이 Linux PC의 집 내부 주소, **단말 IPv4**는 CT1105-SB 주소입니다. 공유기에서 두 주소를 DHCP 예약해 둡니다. HA 주소는 Linux에서도 접근할 수 있어야 합니다. `.local` 이름이 해결되지 않는 환경에서는 HA의 실제 LAN 주소를 사용합니다.

SDK/NDK는 Google의 해당 운영체제용 도구를 내려받고 SDK 라이선스에는 직접 응답합니다. `SmartFactory.apk`와 `HomePanel.apk`는 사용자 설정으로 새로 생성됩니다. Linux에서는 `.command` 대신 `.sh`를 사용합니다.

이 PC의 브라우저에서 `http://localhost:4173`을 열어 **Home Assistant 연결**을 완료합니다. 브라우저가 없는 서버라면 SSH의 로컬 포트 전달로 관리자 화면만 접속할 수 있습니다.

```sh
ssh -L 4173:127.0.0.1:4173 SERVER_USER@SERVER_HOST
```

`SERVER_USER`와 `SERVER_HOST`를 본인 계정·호스트로 바꾼 뒤 접속한 컴퓨터에서 `http://localhost:4173`을 엽니다. 4173 포트를 LAN 전체에 공개하지 않습니다.

## 3. 단말 연결과 USB

단말에서 서버의 TCP **4181, 4186**에 접근할 수 있어야 합니다. Linux 방화벽을 사용하는 경우 본인 CT1105-SB IP에서 오는 이 두 포트만 허용합니다. 포트 포워딩이나 방화벽 전체 해제는 필요하지 않습니다. UFW가 이미 활성화된 Ubuntu의 예:

```sh
read -r -p 'CT1105-SB IPv4: ' PANEL_DEVICE_IP
sudo ufw allow from "$PANEL_DEVICE_IP" to any port 4181 proto tcp
sudo ufw allow from "$PANEL_DEVICE_IP" to any port 4186 proto tcp
```

USB 메모리에 생성한 `hardware/panel-app/SmartFactory.apk`를 복사하고 안전하게 꺼냅니다. 이후 **[공통 설치 단계 5~6](INSTALL_KO.md#5-usb로-최초-설치)**를 따릅니다. USB 디버깅 켜기, 설치된 앱의 기본 홈 설정 승인, USB 없는 재부팅 확인은 Linux에서도 동일합니다. 설치 확인 명령은 Linux 터미널에서 실행합니다.

```sh
python3 scripts/verify-install.py --confirm-screen
python3 hardware/panel_maintenance_server.py withdraw
```

## 4. Linux 자동 실행 (선택)

`Start.sh`를 실행한 창에서 Ctrl+C로 종료한 다음, systemd 사용자 세션에서 실행합니다.

```sh
python3 scripts/linux-service.py install
python3 scripts/linux-service.py status
```

현재 사용자용 서비스를 등록합니다. 시스템 전체 서비스나 root 계정으로 등록하지 않습니다. 해제는 `python3 scripts/linux-service.py uninstall`이며 설정·키는 남습니다. 서버가 절전 상태이거나 저장장치가 분리되면 단말은 연결할 수 없습니다.

로그인하지 않은 상태의 부팅 실행이 필요하면 운영체제의 사용자 서비스·linger 설정을 별도로 검토합니다. HA 가상 머신/컨테이너의 시작도 별개입니다. WSL에서는 systemd 서비스가 Windows 부팅이나 WSL 시작 자체를 보장하지 않습니다.

## 5. 문제 해결

| 현상 | 확인 |
| --- | --- |
| `EADDRNOTAVAIL` | 설정한 서버 IPv4가 이 Linux 네트워크 인터페이스의 주소인지 |
| 파일 권한 오류 | 일반 사용자 소유의 Linux 파일시스템인지, sudo로 만든 파일이나 Windows 공유 경로인지 |
| SDK 호스트 미지원 | `uname -m`이 x86_64인지; Linux ARM64용 Android 빌드는 제공하지 않음 |
| JDK를 찾지 못함 | `javac`가 JDK 17 이상인지, `JAVA_HOME`이 오래된 다른 OS 경로인지 |
| clang/sanitizer 오류 | clang과 libclang-rt-dev 패키지 설치 여부 |
| 자동 실행에서 Node를 못 찾음 | 설치 때와 같은 사용자·Node 경로인지. Node 설치 위치 변경 후 서비스 재등록 |

고급 UART 캡처 도우미는 현재 macOS 전용입니다. Linux/WSL의 이 설치 경로는 UART를 사용하지 않습니다.
