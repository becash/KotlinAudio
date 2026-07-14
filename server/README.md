# becash-player API server

Server FastAPI care stă în fața bazei MySQL `played`. Aplicația Android nu
mai vorbește JDBC direct cu MySQL — trimite HTTPS aici, cu antetul
`X-Api-Key`.

## Endpoints

| Metodă | Cale      | Descriere                                                        |
|--------|-----------|------------------------------------------------------------------|
| GET    | `/health` | Verificare că serverul e viu (fără auth).                        |
| GET    | `/played` | Toată tabela `played` ca `{"songs": {id: {...}}}`.               |
| POST   | `/ops`    | Listă de operații aplicate în ordine, într-o singură tranzacție. |

Corpul pentru `/ops` are exact formatul cozii offline din aplicație:

```json
{
  "ops": [
    {"type": "incrementPlays", "songId": "/muzica/piesa.mp3"},
    {"type": "addListen", "songId": "/muzica/piesa.mp3", "milliseconds": 183000, "duration": 240000},
    {"type": "setRateDance", "songId": "/muzica/piesa.mp3", "rate": 4, "dance": 1, "calm": 0}
  ]
}
```

## Rulare în Docker (Portainer)

Serverul rulează ca un container construit din `Dockerfile`, pornit prin
`docker-compose.yml`. Portainer (web editor) nu poate construi imaginea din
fișiere locale, așa că întâi construiești imaginea pe VPS, apoi creezi stack-ul:

```bash
# pe VPS, în folderul server/
docker build -t becash-player-api:latest .
```

Apoi în Portainer: **Stacks → Add stack → Web editor**, lipești conținutul
din `docker-compose.yml` și definești variabilele de mediu ale stack-ului
(secțiunea *Environment variables*):

| Variabilă        | Exemplu                | Obs.                                      |
|------------------|------------------------|-------------------------------------------|
| `API_KEY`        | `openssl rand -hex 32` | obligatoriu — fără ea containerul se oprește |
| `MYSQL_HOST`     | `player-mysql`         | numele containerului MySQL (default)       |
| `MYSQL_PORT`     | `3306`                 |                                            |
| `MYSQL_USER`     | `becash`               |                                            |
| `MYSQL_PASSWORD` | `...`                  |                                            |
| `MYSQL_DB`       | `becash_player`        |                                            |

(Alternativ, dacă folosești stack-ul *from git repository*, Portainer poate
construi singur imaginea din `build: .`.)

Conectarea la MySQL: containerul `player-mysql` e accesibil în rețeaua Docker
`permanent`, declarată `external` în compose — rețeaua trebuie să existe deja
(o vezi în Portainer la *Networks*), iar stack-ul doar se atașează la ea.
`MYSQL_HOST` are deja default `player-mysql`, deci nu trebuie setat decât dacă
schimbi numele containerului.

Smoke test după pornire (de pe VPS — portul 8080 e legat doar de 127.0.0.1):

```bash
curl http://127.0.0.1:8080/health
curl -H "X-Api-Key: CHEIA_TA" http://127.0.0.1:8080/played
```

Iar din exterior, după configurarea nginx (secțiunea următoare):

```bash
curl https://ci.md/player/health
curl -H "X-Api-Key: CHEIA_TA" https://ci.md/player/played
```

## HTTPS — nginx pe host, URL public `https://ci.md/player`

Containerul vorbește HTTP simplu și e legat doar de `127.0.0.1:8080`; TLS-ul
îl face nginx-ul de pe host. De adăugat în blocul `server { listen 443 ssl; }`
existent pentru `ci.md`:

```nginx
    # becash-player API — https://ci.md/player/... → containerul becash-player-api
    location /player/ {
        # Slash-ul final din proxy_pass taie prefixul /player:
        # /player/played → /played, /player/ops → /ops.
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 30s;
    }

    # /player fără slash → /player/ (altfel nginx nu-l prinde în location-ul de sus)
    location = /player {
        return 301 /player/;
    }
```

Apoi:

```bash
sudo nginx -t && sudo systemctl reload nginx
curl https://ci.md/player/health
```

Compose-ul setează deja `ROOT_PATH=/player`, deci și documentația interactivă
merge corect la `https://ci.md/player/docs`.

În aplicație setezi `https://ci.md/player` ca „URL server API" și cheia din
variabilele stack-ului.

## Note

- La pornire serverul creează singur baza și tabela `played` dacă lipsesc
  (același DDL pe care îl folosea aplicația).
- Fără `API_KEY` setat serverul refuză să pornească.
- Operațiile cu `type` necunoscut sunt sărite și raportate în `skipped`,
  ca o intrare coruptă din coada offline să nu blocheze restul.
