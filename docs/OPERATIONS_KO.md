# 운영, 업데이트와 문제 해결

## 평소 조작

리모컨 방향키로 항목을 이동하고 확인으로 실행합니다. 뒤로 메뉴는 **돌아가기 / 패널 새로고침 / 대기 화면 / 관리**입니다. 대기 화면은 검정 시계이며 키를 누르면 패널로 돌아옵니다. 방과 장면은 HA 설정에서 가져오고, 실제 리모컨 키 호환성은 기기에서 확인합니다.

세탁 완료 알림은 패널이 수신한 상태 변화로 표시됩니다. 스마트폰 푸시 알림과 집 밖 접속은 이번 공개판의 자동 설치 범위에 포함되지 않습니다. 패널을 꺼 둔 동안의 모든 이벤트를 수신한다고 보장하지 않습니다.

## 설정 변경

`node scripts/setup.mjs`를 다시 실행해 HA 주소·방·장면을 바꾼 뒤 서버를 다시 시작합니다. UI 코드를 바꿨다면 `npm --prefix panel run build`도 실행합니다. 서버 또는 단말의 IP, 단말 연결 토큰이 바뀌면 Android 설정 생성과 앱 빌드·배포도 다시 해야 합니다. 원격 연결 자체가 끊긴 경우 USB 설치 도우미로 복구합니다.

기존 `runtime/`를 지우면 연결정보와 키를 잃습니다. 설정을 보관할 때는 암호화된 개인 백업을 사용하고 GitHub에는 올리지 않습니다.

## 원격 앱 업데이트

1. 정상 동작하는 설치를 `scripts/verify-install.py --confirm-screen`으로 먼저 보관합니다.
2. 앱을 수정하고 `hardware/panel-app/AndroidManifest.xml`의 `versionCode`를 현재 설치 및 이미 배포한 버전보다 크게, `versionName`을 구별할 수 있게 올립니다. 같은 버전 코드를 다시 쓰지 않습니다.
3. `python3 scripts/android_setup.py`와 `python3 hardware/panel-app/build.py`를 실행합니다. 기존 키를 유지합니다.
4. `python3 hardware/panel_maintenance_server.py publish hardware/panel-app/HomePanel.apk`로 등록합니다. 실행 중인 관리 서버에 단말이 접속하면 업데이트합니다.
5. `python3 hardware/panel_maintenance_server.py status`와 단말의 **관리 상태**를 비교하고 실제 화면을 확인합니다.
6. 새 버전이 정상인 것을 확인한 뒤 다시 `verify-install.py --confirm-screen`으로 보관하고 `panel_maintenance_server.py withdraw`로 등록을 철회합니다.

웹 화면만 수정했다면 웹 빌드 후 패널 새로고침으로 반영할 수 있습니다. APK를 다시 만들 필요는 없습니다.

## 정상 앱으로 돌아가기

정상으로 확인한 버전은 `runtime/panel-maintenance/known-good-catalog.json`에 기록됩니다. 현재 설정의 지문, 앱 서명과 체크섬이 맞는 항목만 복구용으로 사용할 수 있습니다. 과거 앱 내용을 더 높은 버전 번호로 포장하므로 Android의 버전 감소 설치에 기대지 않습니다.

```sh
python3 hardware/panel_recovery.py fingerprint
python3 hardware/panel_recovery.py prepare --source-sha SHA256 --installed-version VERSION_CODE
```

`SHA256`과 `VERSION_CODE`는 각각 보관한 정상 앱의 SHA256과 현재 설치 버전 숫자로 바꿉니다. `prepare`는 복구 파일을 준비하고 결과 경로를 출력할 뿐 배포하지 않습니다. 결과를 검토한 후 출력된 복구 APK 경로로 `panel_maintenance_server.py publish`를 실행합니다. 관리 연결이 끊겨 있으면 원격 복구는 진행되지 않으므로 USB 도우미가 필요합니다. 이 절차는 앱 복구이며 펌웨어 전체 복구가 아닙니다.

기본 홈 변경을 되돌리려면 단말에서 **뒤로 → 관리 → 기존 홈으로 복원**을 선택합니다. 저장한 기존 홈 설정을 복원하고 패널의 자동 시작과 원격 업데이트를 중단합니다.

## 문제 해결

| 현상 | 먼저 확인할 것 |
| --- | --- |
| 서버의 패널이 안 열림 | 운영체제별 Start 도우미 터미널 오류, 운영체제별 Setup 도우미 완료 여부, 4173 포트 충돌 |
| HA 로그인 실패 | 설정한 HA 기본 주소가 서버에서 열리는지, HA 계정과 연결 권한 |
| 센서가 비어 있음 | HA 엔티티의 실제 값·단위·영역 배정, 대표 센서 선택 |
| 에어컨 버튼이 없음 | Tuya 원터치 장면을 HA에서 다시 불러왔는지, 설정한 scene ID |
| 단말이 서버 연결 중에서 멈춤 | 서버 실행 여부, 두 기기의 IP, Wi-Fi 격리/게스트망, 4181 접근과 방화벽 |
| USB 앱이 안 열림 | USB 최상위 SmartFactory.apk, 안전한 꺼내기, CT1105-SB 모델·펌웨어 차이 |
| 설치 도우미는 열리지만 설치 안 됨 | 서버에 HomePanel.apk를 publish했는지, 4186 서버, 같은 설치의 키·설정인지 |
| `backup_preconditions` 또는 보안 조건 오류 | USB 디버깅이 켜져 있는지, 해당 펌웨어의 제조사 서비스가 검토된 파일과 일치하는지 |
| USB를 빼면 KT 홈이 뜸 | 설치된 앱에서 두 번째 ‘기본 홈·자동 시작 설정’을 승인했는지 |
| ‘시스템 유효성 검사’ 뒤 재부팅 | v47(`1.39`) 이상에서 다시 빌드한 `SmartFactory.apk`·`HomePanel.apk`(둘 다 AOSP 공개 플랫폼 키 서명)로 설치했는지 확인. 그래도 반복되면 초기화하지 말고 앱 버전·펌웨어 버전과 화면 상태를 기록해 해당 펌웨어의 허용 서명 목록 차이를 확인 |
| verify-install이 실패 | 최근 2분 안의 단말 관리 보고, 기본 홈 panel, rootStatus ok, 설치 버전·등록 APK 일치 |
| 전원 복구 후 서버 서버가 안 뜸 | 사용자 로그인/FileVault, 외장 SSD 마운트, HA 가상 머신 실행 상태 |

고급 진단·백업 메뉴는 일반 설치에 필요하지 않습니다. 이 경로의 원본 파일, 기기 식별번호와 로그는 개인 자료로 취급합니다. 이슈에는 본인 주소·토큰·일련번호를 제외한 모델, 앱 버전, 단계와 오류 종류만 적습니다.

## 개발 시 검사

```sh
npm --prefix panel ci
npm --prefix panel test
npm --prefix panel run build
python3 -B -m unittest discover -s tests
python3 -B -m unittest discover -s hardware/tests
```

Android 빌드는 추가로 Java 정책 테스트·네이티브 빌드·APK 서명 검사를 실행합니다. JDK/SDK가 필요한 검사는 해당 도구를 설치한 환경에서 수행합니다. 테스트는 임시 설정과 모의 HA를 사용하며 사용자의 실제 가전 제어를 자동 실행하지 않습니다.

운영체제별 시작·방화벽·자동 실행 방법은 [Mac](INSTALL_KO.md), [Linux](LINUX_KO.md), [Windows/WSL2](WINDOWS_KO.md) 문서를 참고합니다.

공개용으로 깨끗한 소스 사본을 만든 뒤 다음 검사도 실행합니다. 실제 설치 폴더는 runtime과 생성 APK가 있어 검사에 실패하는 것이 정상입니다.

```sh
python3 scripts/privacy-audit.py <공유할-소스-폴더>
```
