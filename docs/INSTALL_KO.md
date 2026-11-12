# CT1105-SB + Mac mini 설치

모든 명령은 다운로드한 `KT_TABLE_TV` 폴더에서 실행합니다. USB 메모리는 최초 설치용이며, 설치를 마친 뒤에는 단말 내부의 앱이 실행됩니다. Mac mini와 Home Assistant는 계속 켜져 있어야 합니다.

## 1. 준비

- CT1105-SB, 정상 전원 어댑터, 작동하는 리모컨, USB 메모리
- Mac mini와 설치 폴더를 둘 저장장치. 외장 SSD를 사용해도 됩니다.
- 집 내부 네트워크에서 서로 통신 가능한 Mac·Home Assistant·CT1105-SB
- Node.js **22.12 이상**, Python **3.10 이상**, JDK **17 이상**, Xcode Command Line Tools
- 처음 Android SDK/NDK를 받기 위한 인터넷과 수 GB 이상의 여유 공간

Node.js와 JDK는 각각 [Node.js 공식 배포](https://nodejs.org/en/download), [Eclipse Temurin JDK](https://adoptium.net/temurin/releases/) 등 공급자의 macOS 설치본을 사용합니다. Python이 없다면 [Python 공식 macOS 배포](https://www.python.org/downloads/macos/)를 설치합니다. Mac 아키텍처에 맞는 JDK를 선택합니다. Command Line Tools는 터미널에서 `xcode-select --install`로 설치할 수 있습니다.

```sh
node --version
python3 --version
javac -version
xcode-select -p
```

Mac과 단말의 IP는 공유기의 DHCP 예약으로 유지하는 구성을 권장합니다. 주소와 비밀번호는 본인의 것을 사용하며, 이 프로그램은 공유기 설정을 자동으로 바꾸지 않습니다. 단말이 예전 올레 TV 인터넷 안내를 표시해도 일반 인터넷과 TV 서비스 판정은 다를 수 있습니다. 기가지니 홈·설정에서 실제 Wi-Fi 연결 상태를 먼저 확인합니다.

Home Assistant가 없다면 [HA 준비와 통합 연결](HOME_ASSISTANT_KO.md)을 먼저 진행합니다. HA 로그인과 센서 조회가 동작해야 합니다.

## 2. 저장소 받기와 Mac 패널 설정

[GitHub 저장소](https://github.com/dasjjl-svg/KT_TABLE_TV)의 **Code → Download ZIP**으로 받은 뒤 압축을 풉니다. 또는 다음을 실행합니다.

```sh
git clone https://github.com/dasjjl-svg/KT_TABLE_TV.git
cd KT_TABLE_TV
./Setup.command
```

Finder에서 `Setup.command`를 열어도 됩니다. 실행 권한 문제라면 프로젝트 폴더에서 `zsh Setup.command`를 사용합니다. 도우미가 아래 값을 묻습니다.

| 입력 | 의미 |
| --- | --- |
| HA URL | 본인 HA 주소. `http://homeassistant.local:8123`처럼 경로가 없는 기본 주소 |
| Mac 주소 | 단말에서 접근할 Mac의 집 내부 IPv4 주소 |
| 단말 주소 | CT1105-SB의 집 내부 IPv4 주소. Mac과 달라야 함 |
| 첫 번째·두 번째 방 | HA에서 지정한 실제 영역 이름 |
| 각 방의 켜기·끄기 장면 | 선택 사항인 `scene.*` 엔티티 ID. 없으면 생략 |

도우미는 설정과 새 연결 토큰을 `runtime/`에 저장하고, npm 의존성을 설치한 뒤 웹 화면을 빌드합니다. 기존 설정으로 다시 실행하면 정상적인 기존 키를 유지합니다. HA 암호를 이 터미널에 입력하지 않습니다.

## 3. Android 앱 만들기

```sh
./Build-Android.command
```

이 도우미는 JDK를 확인하고 Google의 SDK/NDK 도구를 내려받습니다. SDK 라이선스 화면에는 직접 응답합니다. 이어 새 설치용 서명과 업데이트 키를 만들고 Android 5.1.1용 앱 두 개를 빌드·검증합니다.

| 생성 파일 | 용도 |
| --- | --- |
| `hardware/panel-app/SmartFactory.apk` | USB에서 실행할 최초 설치 도우미 |
| `hardware/panel-app/HomePanel.apk` | 단말 내부에 설치할 관리형 홈 패널 |

JDK 인식이 안 되면 설치된 JDK 17 이상을 `JAVA_HOME`에 지정한 터미널에서 다시 실행합니다. SDK 도구 위치는 `runtime/android-toolchain.json`에 저장합니다. 공개 소스에 로컬 경로를 써 넣지 않습니다.

앱 서명에는 기기 호환용 AOSP 공개 테스트 키도 사용됩니다. 이것은 비밀 인증 수단이 아닙니다. 서버 업데이트의 별도 서명키와 연결 토큰은 설치마다 새로 만듭니다. 자세한 출처는 [외부 구성요소](../THIRD_PARTY_NOTICES.md)에 있습니다.

## 4. 설치할 앱 등록과 서버 실행

```sh
python3 hardware/panel_maintenance_server.py publish hardware/panel-app/HomePanel.apk
./Start.command
```

`publish`는 본인 Mac 내부 업데이트 수신기에 앱을 등록하는 명령입니다. 인터넷에 앱을 올리지 않습니다. 등록은 24시간 유효합니다. 다음날 최초 설치를 이어가면서 `no_release`가 나오면 `python3 hardware/panel_maintenance_server.py withdraw` 후 같은 APK를 다시 publish합니다. 설치가 끝난 뒤 앱 내용을 수정할 때는 버전 코드를 올립니다. `Start.command`를 실행한 터미널은 설치 중 열어 둡니다. 종료하려면 Ctrl+C를 누릅니다.

| 접속 | 용도 |
| --- | --- |
| Mac의 `http://localhost:4173` | HA 연결과 웹 패널 |
| 설정한 Mac 주소의 4181 포트 | 구형 단말용 화면, 지정한 단말·토큰으로 접근 |
| 설정한 Mac 주소의 4186 포트 | 관리 상태와 서명된 앱 업데이트 |

Mac 방화벽이 물으면 해당 프로그램의 집 내부 연결만 허용합니다. 공유기 포트 포워딩·DMZ·방화벽 전체 해제는 필요하지 않습니다.

Mac 브라우저에서 `http://localhost:4173`을 열고 **Home Assistant 연결**을 선택합니다. HA의 본인 계정으로 로그인한 뒤 센서와 방 이름이 표시되는지 확인합니다. 장면·센서 연결은 [HA 안내](HOME_ASSISTANT_KO.md)를 참고합니다.

## 5. USB로 최초 설치

1. USB 메모리의 최상위 폴더에 `SmartFactory.apk`를 복사합니다. 다른 파일을 지우거나 메모리를 포맷할 필요는 없습니다. 기존 같은 이름의 파일이 있다면 먼저 본인 Mac에 보관합니다.
2. Mac에서 USB를 안전하게 꺼냅니다. CT1105-SB 전원을 끄고 USB를 꽂은 뒤 켭니다.
3. 도우미와 Wi-Fi가 올라올 때까지 기다립니다. 먼저 **뒤로 → 관리 → 고급 진단 → 개발자 옵션**을 열어 사용 스위치와 **USB 디버깅**을 켭니다. 확인창이 나오면 직접 확인합니다. 이 설정은 설치·관리 동작의 필수 검사 조건이며, Mac과 USB 케이블을 연결하거나 네트워크 ADB 시험을 실행할 필요는 없습니다.
4. 패널로 돌아와 **뒤로 → 홈 패널 설치·업데이트**를 한 번 선택합니다.
5. 도우미가 Mac에서 관리형 앱을 받아 검증·설치하고 엽니다. 완료 전에 전원이나 USB를 빼지 않습니다.
6. **새로 설치된 앱**에서 **뒤로 → 관리 → 기본 홈·자동 시작 설정**을 선택하고 확인창을 읽은 뒤 승인합니다. 이 두 번째 단계가 기본 홈을 바꾸는 단계입니다. USB 도우미 실행만으로는 기본 홈이 바뀌지 않습니다.

실패하면 계속 같은 버튼을 누르지 말고 [문제 해결](OPERATIONS_KO.md)을 확인합니다. 초기화·펌웨어 업그레이드는 이 설치 절차에 포함되지 않습니다. 이미 다른 서명의 설치 도우미가 남아 있는 기기는 새 설치와 조건이 다릅니다. 관리 실행은 검토된 제조사 서비스 파일의 크기·SHA와 기기 보안 설정도 대조합니다. 다른 펌웨어에서 `backup_preconditions` 등으로 중단되면 검사를 삭제해서 진행하지 말고 호환성 확인 대상으로 기록합니다. USB 디버깅을 나중에 끄면 설치·관리 작업이 중단될 수 있습니다.

## 6. USB 없는 부팅 확인

1. 관리 상태에서 설치 버전, 연결 상태와 기본 홈 정보를 확인합니다.
2. 전원을 끄고 USB를 뺀 뒤 다시 켭니다. Wi-Fi 연결까지 1~2분 기다립니다.
3. 패널이 자동으로 뜨고 유지되는지, 방향키·확인·뒤로가 작동하는지 확인합니다.
4. **뒤로 → 대기 화면**에서 검은 시계 화면이 뜨고 방향키로 돌아오는지 확인합니다.
5. Mac에서 다음을 실행합니다. 이 명령은 방금 본 화면의 정상 동작을 직접 확인했다는 표시입니다.

```sh
python3 scripts/verify-install.py --confirm-screen
python3 hardware/panel_maintenance_server.py withdraw
```

검증 도우미는 최근 단말 보고와 서명된 앱 이력을 대조하고 정상 앱을 비공개 보관합니다. 2분 이내의 관리 보고가 없거나 버전·기본 홈이 다르면 성공으로 기록하지 않습니다. `withdraw`는 설치가 끝난 업데이트의 추가 배포만 중단하며 설치된 앱은 지우지 않습니다.

## 7. Mac 로그인 후 자동 실행 (선택)

먼저 `Start.command`의 터미널을 Ctrl+C로 종료한 뒤 실행합니다.

```sh
python3 scripts/mac-service.py install
python3 scripts/mac-service.py status
```

이 설정은 현재 Mac 사용자 로그인 후 패널 서버를 실행합니다. 설치 폴더를 옮기면 재등록해야 하며 외장 SSD는 로그인 시 연결되어 있어야 합니다. HA 가상 머신의 자동 시작은 별도로 설정해야 합니다. FileVault를 사용하는 Mac은 정전 후 부팅에서 사용자 잠금 해제가 필요할 수 있습니다.

자동 실행을 해제하려면 `python3 scripts/mac-service.py uninstall`을 실행합니다. 사용자 설정과 앱은 남습니다.
