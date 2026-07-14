"""API server pentru becash-player.

Stă în fața bazei MySQL `played` și expune operațiile pe care aplicația
Android le făcea până acum direct prin JDBC. Payload-urile /ops au exact
formatul din OfflineQueue.kt, ca telefonul să poată goli coada offline
într-o singură cerere.

Configurare prin variabile de mediu (vezi .env.example):
    API_KEY, MYSQL_HOST, MYSQL_PORT, MYSQL_USER, MYSQL_PASSWORD, MYSQL_DB
"""

import os
import secrets
from contextlib import asynccontextmanager, contextmanager
from typing import Dict, List, Optional

import pymysql
from fastapi import Depends, FastAPI, HTTPException, Security
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, ConfigDict

MYSQL_HOST = os.environ.get("MYSQL_HOST", "127.0.0.1")
MYSQL_PORT = int(os.environ.get("MYSQL_PORT", "3306"))
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
MYSQL_PASSWORD = os.environ.get("MYSQL_PASSWORD", "")
MYSQL_DB = os.environ.get("MYSQL_DB", "becash_player")
API_KEY = os.environ.get("API_KEY", "")

DDL = """
    CREATE TABLE IF NOT EXISTS played (
        id       VARCHAR(500) PRIMARY KEY,
        plays    INT        DEFAULT 0,
        rate     TINYINT    DEFAULT 0,
        dance    TINYINT(1) DEFAULT 0,
        calm     TINYINT(1) DEFAULT 0,
        listen   BIGINT     DEFAULT 0,
        duration INT        DEFAULT 0,
        updated  DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
"""


@contextmanager
def db(database: Optional[str] = MYSQL_DB):
    conn = pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=database,
        charset="utf8mb4",
        connect_timeout=5,
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not API_KEY:
        raise RuntimeError("API_KEY nu e setat — serverul nu pornește fără cheie.")
    with db(database=None) as conn, conn.cursor() as cur:
        cur.execute(
            f"CREATE DATABASE IF NOT EXISTS `{MYSQL_DB}` "
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        )
        cur.execute(f"USE `{MYSQL_DB}`")
        cur.execute(DDL)
    yield


# ROOT_PATH = prefixul public din spatele reverse proxy-ului (ex. /player),
# necesar doar ca /docs și schema OpenAPI să genereze URL-uri corecte.
app = FastAPI(
    title="becash-player API",
    lifespan=lifespan,
    root_path=os.environ.get("ROOT_PATH", ""),
)

api_key_header = APIKeyHeader(name="X-Api-Key", auto_error=False)


def require_api_key(key: Optional[str] = Security(api_key_header)) -> None:
    if not key or not secrets.compare_digest(key, API_KEY):
        raise HTTPException(status_code=401, detail="Cheie API invalidă")


class Op(BaseModel):
    # Ignorăm câmpuri necunoscute (ex. "ts" pus de OfflineQueue) ca o coadă
    # veche de pe telefon să nu blocheze flush-ul.
    model_config = ConfigDict(extra="ignore")

    type: str
    songId: str
    milliseconds: int = 0
    duration: int = 0
    rate: int = 0
    dance: int = 0
    calm: int = 0


class OpsRequest(BaseModel):
    ops: List[Op]


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/played", dependencies=[Depends(require_api_key)])
def played() -> dict:
    with db() as conn, conn.cursor() as cur:
        cur.execute("SELECT * FROM played")
        songs: Dict[str, dict] = {}
        for row in cur.fetchall():
            song_id = row.pop("id")
            if row.get("updated") is not None:
                row["updated"] = row["updated"].isoformat(sep=" ")
            songs[song_id] = row
    return {"songs": songs}


@app.post("/ops", dependencies=[Depends(require_api_key)])
def apply_ops(req: OpsRequest) -> dict:
    """Aplică operațiile în ordine, într-o singură tranzacție.

    Tipurile necunoscute sunt sărite (nu 400), ca o intrare coruptă din coada
    offline să nu blocheze pentru totdeauna restul operațiilor.
    """
    applied = 0
    skipped = 0
    with db() as conn, conn.cursor() as cur:
        for op in req.ops:
            if op.type == "incrementPlays":
                cur.execute(
                    "INSERT INTO played (id, plays) VALUES (%s, 1) "
                    "ON DUPLICATE KEY UPDATE plays = plays + 1",
                    (op.songId,),
                )
            elif op.type == "addListen":
                cur.execute(
                    "INSERT INTO played (id, listen, duration) VALUES (%s, %s, %s) "
                    "ON DUPLICATE KEY UPDATE listen = listen + %s, "
                    "duration = IF(duration = 0, VALUES(duration), duration)",
                    (op.songId, op.milliseconds, op.duration, op.milliseconds),
                )
            elif op.type == "setRateDance":
                cur.execute(
                    "INSERT INTO played (id, rate, dance, calm) VALUES (%s, %s, %s, %s) "
                    "ON DUPLICATE KEY UPDATE rate = %s, dance = %s, calm = %s",
                    (op.songId, op.rate, op.dance, op.calm, op.rate, op.dance, op.calm),
                )
            else:
                skipped += 1
                continue
            applied += 1
    return {"applied": applied, "skipped": skipped}
