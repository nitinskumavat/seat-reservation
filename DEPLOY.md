# Deploy to a VPS

One Linux VPS runs the app and Postgres with `compose.prod.yaml`, served at `http://<ip>:8080`.
Tested sizes: 2 vCPU with 8 GB or 4 GB RAM. Under a full burst the whole stack, dashboard
included, used about 1 GB.

## 1. Prepare the server (once)

```bash
curl -fsSL https://get.docker.com | sh          # Docker + Compose plugin
sudo systemctl enable docker                    # start on boot; containers restart themselves
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile \
  && sudo mkswap /swapfile && sudo swapon /swapfile \
  && echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # safety net for memory spikes
```

Open inbound TCP **8080** (API) in your provider's firewall, and **3000** if you run the
dashboard. Nothing else is published: Postgres and Prometheus are only reachable inside Docker.
Docker-published ports bypass `ufw`, so not publishing them is what keeps them private.

## 2. Configure and start

```bash
git clone https://github.com/nitinskumavat/seat-reservation.git && cd seat-reservation
cp .env.example .env
sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 16)/; \
        s/^ADMIN_TOKEN=.*/ADMIN_TOKEN=$(openssl rand -hex 16)/; \
        s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 32)/; \
        s/^GRAFANA_ADMIN_PASSWORD=.*/GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 12)/" .env
# On a 4 GB server, also set APP_MEM_LIMIT=1536m and PG_SHARED_BUFFERS=256MB in .env.

docker compose -f compose.prod.yaml up -d --build                        # API only
docker compose -f compose.prod.yaml --profile monitoring up -d --build   # API + dashboard
curl http://localhost:8080/actuator/health/readiness                     # {"status":"UP"}
```

The data lives in a Docker volume, so it survives restarts and redeploys. A crashed container
restarts automatically.

## 3. Verify from your laptop

```bash
ADMIN_TOKEN=<value from the server's .env> ./burst.sh http://<ip>:8080
```

Run it from outside the server. A client on the same 2 vCPUs would compete with the service and
skew the numbers.

## Operate

| Task | Command (on the server) |
|---|---|
| Live logs | `docker compose -f compose.prod.yaml logs -f app` |
| Dashboard | `http://<ip>:3000`: anyone can view; admin login is `admin` / `GRAFANA_ADMIN_PASSWORD` |
| Redeploy after `git pull` | `docker compose -f compose.prod.yaml up -d --build` |
| Stop | `docker compose -f compose.prod.yaml --profile monitoring down` |
| Stop and delete all data | add `-v` to the stop command |

If the dashboard's connection-pool panel shows **pending** staying high during a burst, try
`DB_POOL_SIZE=10` in `.env` and redeploy. On 2 vCPUs a smaller pool often performs better.
