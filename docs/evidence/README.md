# Compact Verification Evidence

This directory contains a compact copy of local `reports/`: small result JSON files, experiment plans and conditions, source-code SHA-256 hashes, synthetic datasets, and cache experiment summaries. Subdirectories preserve the original relative hierarchy in `reports/`. Files were checked byte for byte when copied; measurements, timestamps, image IDs, and historical path metadata were not changed. Only prose in the Markdown evidence summary was subsequently translated into English; measured JSON and data files remain unchanged.

Complete raw JSONL, run logs, Maven XML, metric snapshots, raw per-request burst JSON, and baseline source code remain in the original computer's local `reports/` and were not uploaded. Local absolute paths and strings beginning with `reports/` retained in the JSON are historical run metadata, not clickable file links in a new clone. To verify again, run the repository's test and experiment scripts; new results are written to the Git-ignored local `reports/` directory.

Product, order, payment, and load data are all local synthetic test data. Simulated payments do not create real charges; these reports do not represent real business users, production capacity, or completed cloud deployment. Selected files were checked for common secret formats and credential fields. They do not include `.env`, database dumps, private keys, cloud credentials, or kubeconfig.

## Entry points

- [Initial local verification summary](verification-summary.json) and [corresponding source hashes](source-hashes.json).
- [Post-optimization cache verification summary](cache-optimization/verification.json).
- [All cache measurement rounds and warmup summary](cache-study-final/summary.md) and [machine-readable version](cache-study-final/summary.json).
- [Copied-file manifest and SHA-256 hashes](manifest.json).

`manifest.json` records original relative paths, repository copies, byte counts, and SHA-256 hashes of the copied files; the entry for the translated Markdown summary reflects its current English content. It establishes copy identity only; it does not turn historical reports into new test results from the current workspace.
