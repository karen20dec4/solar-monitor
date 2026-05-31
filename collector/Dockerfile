FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY collector.py .

# -u = output nebufferat, ca sa vedem log-urile in `docker logs` instant
CMD ["python", "-u", "collector.py"]
