# CLAUDE.md

Claude Code는 이 파일을 자동으로 읽습니다. 저장소 공용 규칙은 `AGENTS.md` 한 곳에만 쓰고, 여기서는 불러오기만 합니다.
파트별 규칙은 각 폴더의 `AGENTS.md`에 있고, 그 폴더의 `CLAUDE.md`가 불러옵니다(예: `be/CLAUDE.md` → `be/AGENTS.md`).

Claude Code는 **저장소 루트(이 폴더)에서 실행**합니다. 하위 폴더(`be/` 등)에서 실행하면 그 폴더 밖의 규칙 문서는 불러오기 허용을 따로 확인해야 해서, 허용하지 않으면 루트 규칙이 빠집니다.

@AGENTS.md
