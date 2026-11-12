# Windows 설치 — WSL2

지원 경로는 **Windows 11 x64 + WSL2 Ubuntu x86_64**입니다. 패널과 Android 빌드는 WSL 안에서 실행하고, HA 로그인은 Windows 브라우저를 사용합니다. Windows 네이티브 Python/Node로 이 저장소의 서버를 실행하는 방식은 지원하지 않습니다. 파일 잠금·소유권·비공개 키 권한 검사를 Linux 환경에서 유지합니다.

## 1. WSL 준비

미러 네트워크를 사용할 수 있는 Windows 11 **22H2 이상**과 최신 WSL2가 필요합니다. 관리자 PowerShell에서 본인 환경에 맞게 설치하고, 요구될 경우 재부팅합니다. 이미 설치한 Ubuntu를 삭제하거나 초기화하지 않습니다.

```powershell
wsl --install -d Ubuntu-24.04
wsl --update
wsl --list --verbose
```

Ubuntu를 처음 열면 일반 Linux 사용자 계정을 만듭니다. 목록에서 Ubuntu의 VERSION이 2인지 확인합니다. 배포판 이름은 실제 설치한 이름을 사용합니다. [Microsoft WSL 설치 안내](https://learn.microsoft.com/en-us/windows/wsl/install)

## 2. 기가지니에서 PC로 접속할 네트워크

Windows 사용자 폴더의 `.wslconfig`에 다음을 추가합니다. 기존 파일이 있으면 다른 설정을 보존하고 같은 섹션의 항목을 수정합니다.

```ini
[wsl2]
networkingMode=mirrored
```

WSL에서 하던 작업을 저장한 후 PowerShell에서 `wsl --shutdown`을 실행하고 Ubuntu를 다시 엽니다. 이 명령은 실행 중인 모든 WSL 배포판을 종료합니다. Ubuntu에서 `wslinfo --networking-mode`가 `mirrored`인지, `ip -4 addr`에 Windows PC의 집 내부 주소가 보이는지 확인합니다.

미러 모드는 LAN에서 WSL로 직접 접속할 수 있게 합니다. 이번 프로그램은 단말의 원래 IP를 검사하므로 기본 NAT + portproxy 경로는 지원하지 않습니다. VPN을 사용하면 미러 연결에 영향을 줄 수 있습니다. [Microsoft WSL 네트워크 안내](https://learn.microsoft.com/en-us/windows/wsl/networking), [설정 파일 설명](https://learn.microsoft.com/en-us/windows/wsl/wsl-config)

## 3. Ubuntu 파일시스템에서 설치

Ubuntu 터미널을 열어 [Linux 준비와 실행](LINUX_KO.md#1-준비)에 따라 Node·Python·JDK·clang을 설치합니다. 저장소는 Ubuntu 안의 홈 폴더에 받습니다.

```sh
cd ~
git clone https://github.com/dasjjl-svg/KT_TABLE_TV.git
cd KT_TABLE_TV
bash Setup.sh
bash Build-Android.sh
python3 hardware/panel_maintenance_server.py publish hardware/panel-app/HomePanel.apk
bash Start.sh
```

서버 주소에는 **미러 네트워크에 표시된 Windows PC의 집 내부 IPv4**를 지정합니다. 기본 NAT의 WSL 가상 IP를 사용하지 않습니다. Node/JDK도 Windows 설치본을 가져오지 않고 Ubuntu용을 설치합니다.

`/mnt/c`·`/mnt/d` 같은 Windows 드라이브에서 프로그램을 실행하면 비공개 파일 권한이 맞지 않을 수 있어 실행 도우미가 중단합니다. WSL의 Linux 파일시스템에 소스·runtime·SDK를 보관하고 완성된 USB 설치 APK만 복사합니다. [Microsoft 파일시스템 안내](https://learn.microsoft.com/en-us/windows/wsl/filesystems)

PowerShell에서 조작하려면 저장소의 `Home-Panel.ps1`도 사용할 수 있습니다. 아래 `USER`는 Ubuntu 사용자명, 배포판 이름은 `wsl --list --verbose`에 나온 이름으로 바꿉니다. Windows 쪽에 보이는 이 스크립트의 폴더에서 실행합니다.

```powershell
./Home-Panel.ps1 -Action setup -Distribution Ubuntu-24.04 -ProjectPath /home/USER/KT_TABLE_TV
./Home-Panel.ps1 -Action build-android -Distribution Ubuntu-24.04 -ProjectPath /home/USER/KT_TABLE_TV
./Home-Panel.ps1 -Action start -Distribution Ubuntu-24.04 -ProjectPath /home/USER/KT_TABLE_TV
```

이 도우미는 같은 WSL 설치를 호출하며 별도 Windows용 설정을 만들지 않습니다. Android 빌드 후 앱 등록(`publish`)은 위 Ubuntu 명령으로 진행합니다. PowerShell 실행 정책으로 막히면 Ubuntu에서 `.sh` 도우미를 직접 실행할 수 있습니다.

## 4. 방화벽과 HA 로그인

Windows 네트워크 프로필을 본인의 집 네트워크에 맞게 설정하고, **CT1105-SB 주소에서 TCP 4181·4186으로 오는 연결**만 허용합니다. Windows 방화벽과 Hyper-V 방화벽이 모두 적용될 수 있습니다. 아래는 관리자 PowerShell에서 최초 규칙을 만드는 예입니다. 두 주소에는 본인 기기의 실제 IPv4를 입력합니다.

```powershell
$panelDevice = Read-Host 'CT1105-SB IPv4'
$panelServer = Read-Host 'Windows LAN IPv4'
New-NetFirewallRule -Name 'KTTableTV-LAN' -DisplayName 'KT Table TV LAN' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $panelServer -LocalPort 4181,4186 -RemoteAddress $panelDevice -Profile Private
New-NetFirewallHyperVRule -Name 'KTTableTV-WSL' -DisplayName 'KT Table TV WSL' -Direction Inbound -Action Allow -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalAddresses $panelServer -LocalPorts 4181,4186 -RemoteAddresses $panelDevice
```

이미 같은 이름의 규칙이 있으면 기존 범위를 확인한 뒤 수정합니다. 전체 인바운드 허용이나 방화벽 해제는 하지 않습니다. 규칙이 필요한 환경과 명령은 [Hyper-V 방화벽 설명](https://learn.microsoft.com/en-us/windows/security/operating-system-security/network-security/windows-firewall/hyper-v-firewall), [규칙 명령](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallhypervrule?view=windowsserver2025-ps)을 참고합니다.

Windows 브라우저에서 `http://localhost:4173`을 열어 HA 계정으로 연결합니다. HA가 없다면 [HA 공식 Windows 설치 안내](https://www.home-assistant.io/installation/windows/)로 별도 HA OS 가상 머신을 준비하거나 다른 장치의 기존 HA를 사용합니다. 이 프로젝트의 WSL 설치와 HA OS 설치는 별개입니다.

규칙을 제거할 때는 본인이 이 안내로 만든 두 개만 제거합니다.

```powershell
Remove-NetFirewallRule -Name 'KTTableTV-LAN'
Remove-NetFirewallHyperVRule -Name 'KTTableTV-WSL'
```

## 5. USB 설치와 실행 유지

Ubuntu의 저장소 폴더에서 `explorer.exe .`를 실행하면 Windows 탐색기가 해당 폴더를 엽니다. `hardware/panel-app/SmartFactory.apk`만 USB 최상위로 복사하고 Windows에서 안전하게 꺼냅니다. WSL에 USB 장치를 직접 전달할 필요는 없습니다.

이어 [공통 USB 설치 단계 5~6](INSTALL_KO.md#5-usb로-최초-설치)을 진행합니다. 설정한 서버와 WSL 실행 창은 계속 켜 둡니다. USB 없이 패널이 부팅되는 것을 확인한 뒤 Ubuntu에서 `verify-install.py --confirm-screen`과 `panel_maintenance_server.py withdraw`를 실행합니다.

Windows 재부팅 후에는 Ubuntu를 열고 저장소에서 `bash Start.sh`를 다시 실행합니다. Windows 절전·WSL 종료 시 서버도 사용할 수 없습니다. 선택적 systemd 사용자 서비스는 WSL 내부용이며 Windows 로그인 시 WSL을 자동으로 켜 주지는 않습니다.

## 6. 확인 범위

Windows PowerShell 도우미는 구문·인수 전달 검사를 수행합니다. Linux 실행과 Android 빌드는 별도로 검사합니다. **Windows PC와 실제 CT1105-SB 사이의 WSL 미러 LAN 연결은 설치자의 환경에서 확인해야 합니다.** Windows 10, WSL1, NAT 포트 프록시, Windows ARM64는 이번 지원 범위에 포함하지 않습니다.
