"""Simple Python script to connect to the Postgres instance used by the
Docker Compose environment.

Usage:
    python scripts/connect_db.py

It relies on the database listening on localhost:5432 with the same credentials
configured in docker-compose.yml (user/postgres password/postgres).
"""

import os
import psycopg2
from psycopg2.extras import RealDictCursor


def main():
    # connection parameters match docker-compose configuration
    params = {
        "host": "localhost",
        "port": 5432,
        "dbname": "myhockeystats",
        "user": "postgres",
        "password": "postgres",
    }

    print("Connecting to Postgres at {host}:{port}/{dbname}".format(**params))
    conn = psycopg2.connect(**params)

    try:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT NOW() as now")
            row = cur.fetchone()
            print("Server time:", row['now'])

            # example query to list users
            cur.execute("SELECT id, email, role, created_at FROM users")
            users = cur.fetchall()
            print(f"Found {len(users)} user(s):")
            for u in users:
                print(u)
    finally:
        conn.close()


if __name__ == '__main__':
    main()
