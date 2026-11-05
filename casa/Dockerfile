FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY casa ./casa

# Un singur worker cu 8 fire: un singur proces detine coada de comenzi a fiecarei prize
# (niciodata doua conexiuni TCP catre aceeasi priza).
CMD ["gunicorn", "-b", "0.0.0.0:8100", "-w", "1", "--threads", "8", "--access-logfile", "-", \
     "--access-logformat", "%(h)s %(t)s \"%(r)s\" %(s)s %(b)s %(L)ss \"%(a)s\"", "casa.web:create_app()"]
