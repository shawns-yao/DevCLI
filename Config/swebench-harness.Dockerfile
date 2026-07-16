FROM python:3.11-slim

WORKDIR /opt/swebench
COPY . .
RUN python -m pip install --no-cache-dir .
ENV PYTHONPATH=/opt/swebench

ENTRYPOINT ["python", "-m", "swebench.harness.run_evaluation"]
