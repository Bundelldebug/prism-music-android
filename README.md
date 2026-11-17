# 프리즘 (Prism) — 9.5.0

글래스 효과 UI를 가진 안드로이드 음악 앱. 음악 서비스는 Spotify와 기기 음악, 한국어/영어/일본어 지원.
외부 라이브러리 없이 안드로이드 기본 API만으로 처음부터 작성했고, 아이콘·그래픽도 모두 직접 그렸습니다.

## 빌드 / 검사
Gradle 없이 JDK 21 + Android build-tools 35.0.0 + platform android-35 로 빌드합니다.
툴체인 폴더(안에 `jdk21\jdk-21.0.13+9`, `build-tools-35.0.0\android-15`, `platforms-android-35\android-35\android.jar`)를
환경 변수 `PRISM_TOOLCHAIN` 으로 주거나, 프로젝트 폴더의 `toolchain.path` 파일 첫 줄에 적어 두세요(커밋되지 않음).
서명 키 `keystore.jks` 는 첫 빌드 때 자동으로 만들어지며 저장소에는 올라가지 않습니다.
```
.\build.ps1              # → dist\PrismMusic_Android.apk (minSdk 31, targetSdk 35)
.\tests\ui\run.ps1       # Robolectric 네이티브 그래픽 스크린샷 → tests\ui\output\
```

## 음악 소스
- **내 기기 음악**(기본값): 휴대폰에 저장된 음악을 앱이 직접 재생. Spotify 불필요. 상단 알약 왼쪽 버튼 메뉴에서 전환.
- **Spotify**: 아래 설정 후 사용. 앱에서 재생할 수 없는 플레이리스트(Spotify 소유·곡 목록 비공개)는 미리 확인해 숨김.

## Spotify 연결 (최초 1회)
1. https://developer.spotify.com/dashboard 에서 앱 생성, **Web API** 선택
2. Redirect URI에 `prismmusic://callback` 추가
3. (개발 모드) User Management에 본인 Spotify 계정 추가
4. 앱 → 설정 → Spotify → Client ID 입력 → Spotify 연결 (PKCE, Client Secret 불필요)

비트퍼펙트: Android 14 이상 + USB DAC에서 기기 음악을 원본 샘플레이트·비트 깊이로 출력(설정 → 재생).

Spotify 곡은 앱 안의 "Prism" 플레이어(Spotify 공식 Web Playback SDK, 숨김 WebView)에서 재생 — Spotify 앱 설치/실행 불필요.
앱을 최근 앱 목록에서 닫아도 재생 중이면 계속 재생되고, 알림·잠금 화면에서 제어할 수 있습니다.
**Spotify Premium 필요**. 가사는 LRCLIB(설정에서 끌 수 있음).

## 화면 구성
| 화면 요소 | 구현 |
|---|---|
| 떠 있는 글래스 탭바 + 분리된 검색 버블 | `Dock.java`, `Glass.java` — AGSL 셰이더(굴절·색 분산·테두리 빛·부드러운 합치기) |
| 탭 선택 렌즈(끌기·확대, 탭바와 검색 버블이 방울처럼 이어짐) | `Dock` 렌즈 스프링 |
| 스크롤 시 탭바가 [홈 ●][미니 플레이어][검색 ●] 한 줄로 축소 | `Dock.setCollapsed` |
| 미니 플레이어 → 전체 화면 재생기(컨테이너 변형, 앨범아트 이동) / 아래로 끌어 닫기 | `NowPlaying.java` |
| 큰 제목 → 상단 작은 제목 + 점진 블러 | `Page.java`, `Header.java` |
| 버튼에서 자라나는 글래스 메뉴 | `GlassMenu.java` |
| 태블릿: 위쪽 텍스트 탭 + 사이드바, 넓은 하단 플레이어 바 | `Dock.java`, `Sidebar.java` |
| 폴더블 펼친 화면: 오른쪽 세로 탭 또는 태블릿 UI (설정 → 화면) | `Dock.railFor` |
| 홈/탐색/보관함/설정(그룹 목록) | `Pages.java`, `Settings.java` |

Spotify는 Spotify AB의 상표이며, 이 앱은 Spotify와 제휴 관계가 없습니다.
