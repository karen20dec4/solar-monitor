FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py app_clients.py system_metrics.py ./

# Un worker cu 8 fire ajunge pentru telefoane; un singur proces tine evidenta lor (app_clients.py) intreaga.
CMD ["gunicorn", "-b", "0.0.0.0:8000", "-w", "1", "--threads", "8", "--access-logfile", "-", "app:app"]
