# AI 서비스 운영 이미지(S15P31E103-256). 빌드 컨텍스트는 ai/ 폴더다.
#   docker build -t yamu-ai:<커밋> ai
# 테스트 단계가 통과해야 실행 이미지가 만들어진다(실행 단계가 테스트를 통과한 코드·모델을 복사한다).
FROM python:3.12.16-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

FROM base AS test
COPY requirements-dev.txt .
RUN pip install -r requirements-dev.txt
COPY app ./app
COPY models ./models
COPY tests ./tests
RUN python -m unittest -v

FROM base AS runtime
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app
# 파일은 root 소유로 두어 서버 프로세스(app)가 코드·모델을 고칠 수 없게 한다(ai/models/README.md 「배포할 때」).
COPY --from=test /app/app ./app
COPY --from=test /app/models ./models
USER app
EXPOSE 8001
# status는 모델을 못 읽어도 ok라서 livingCostModel.loaded로 판정한다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["python", "-c", "import json,os,sys,urllib.request; b=json.load(urllib.request.urlopen('http://127.0.0.1:'+os.getenv('AI_PORT','8001')+'/health', timeout=3)); sys.exit(0 if b['livingCostModel']['loaded'] else 1)"]
CMD ["python", "-m", "app.main"]
