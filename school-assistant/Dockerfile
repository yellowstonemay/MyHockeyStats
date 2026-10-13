# Private school assistant (MyRHScore/assistant) - container image.
#
# Build context = this folder, which must contain `assistant/` (the Python package) - the deploy
# script copies MyRHScore/assistant/*.py in here before building. The database and the spend ledger
# live on ./data, mounted by docker-compose.macmini.yml, so the image stays stateless.
#
# It is NOT published on any port: only the backend container reaches it, over the compose network,
# and the backend gates the endpoints on the is_admin flag.
FROM python:3.12-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    ASSISTANT_DB=/data/assistant.db \
    ASSISTANT_USAGE=/data/ai_usage.json

COPY assistant/ ./assistant/

EXPOSE 8787
CMD ["python", "-m", "assistant.web", "--host", "0.0.0.0", "--port", "8787"]
