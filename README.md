# 키즈노트 다운로더 - Android 앱

## APK 만드는 법 (GitHub 무료 빌드, PC 불필요)

### 1단계 - GitHub 계정 만들기
https://github.com 에서 무료 가입

### 2단계 - 저장소 만들고 파일 올리기
1. github.com 접속 후 우상단 + 버튼 → New repository
2. 이름: kidsnote-downloader → Create repository
3. uploading an existing file 클릭
4. 이 ZIP 안의 모든 파일을 폴더 구조째로 업로드
5. Commit changes 클릭

### 3단계 - APK 자동 빌드 (약 10분)
1. 저장소에서 Actions 탭 클릭
2. 왼쪽 APK 빌드 클릭
3. Run workflow 버튼 클릭
4. 초록 체크 뜨면 완료
5. 클릭 → Artifacts → Kidsnote-Downloader-APK 다운로드

### 4단계 - 폰에 설치
1. ZIP 압축 해제 → app-debug.apk 파일
2. 카카오톡/구글드라이브로 폰에 전송
3. 폰에서 파일 열기 → 출처를 알 수 없는 앱 허용 → 설치

## 사용법
1. 앱 실행 → 키즈노트 로그인
2. 자동 감지 버튼으로 아이 ID 찾기
3. 기간 설정 후 전체 다운로드
4. 완료 후 파일 관리자 → 내부저장소 → Android → data → com.kidsnote.downloader → files
