#!/usr/bin/env python3
"""Run all Java tests against the local Compose PostgreSQL without printing credentials."""
import os
from pathlib import Path
import subprocess
import sys
from api_client import ROOT, read_env


def main():
    config=read_env()
    if not config.get('POSTGRES_PASSWORD'):
        raise SystemExit('Run python3 scripts/init_env.py and start the local Compose database first.')
    env={**os.environ,'TEST_DATABASE_URL':'jdbc:postgresql://127.0.0.1:5432/postgres',
        'TEST_DATABASE_USER':'platform_admin','TEST_DATABASE_PASSWORD':config['POSTGRES_PASSWORD']}
    locator=Path('/usr/libexec/java_home')
    if locator.exists():
        java=subprocess.run([str(locator),'-v','17'],text=True,capture_output=True)
        if java.returncode==0:env['JAVA_HOME']=java.stdout.strip()
    result=subprocess.run([str(ROOT/'mvnw'),'-B','-ntp','-Pintegration',*sys.argv[1:],'verify'],cwd=ROOT,env=env)
    raise SystemExit(result.returncode)


if __name__=='__main__':main()
