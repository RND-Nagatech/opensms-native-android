# OpenSMS Next — Paket Handoff Codex

Ini paket spesifikasi pengembangan **native Android Kotlin SMS Gateway**, berdasarkan repository https://github.com/daslearning-org/OpenSMS.

## File

- `PRD_OpenSMS_Next_Kotlin.md`: PRD detail lengkap, prioritas, UI, API, data, security, lifecycle, test, roadmap.
- `openapi.yaml`: spesifikasi kontrak REST API v1 untuk API tests dan generation.
- `AGENTS.md`: instruksi permanen untuk coding agent.
- `CODEX_MASTER_PROMPT.md`: prompt awal yang dapat langsung ditempel ke Codex.

## Cara memakai

1. Clone/fork repo upstream, buat branch pengembangan Anda.
2. Letakkan empat file spesifikasi ini di root repo kerja.
3. Buka Codex di root repo; tempel isi `CODEX_MASTER_PROMPT.md`.
4. Jalankan Phase 0 terlebih dahulu. Terima hasil audit/spike/ADR dan cek build.
5. Instruksikan Codex melanjutkan Phase 1, 2, dan seterusnya secara terpisah.

**Catatan:** Ini spesifikasi dan handoff; bukan APK atau implementasi Kotlin yang telah diuji.

**Batasan penting:** Android membatasi izin SMS, server background, dan akses LAN API 37; PRD mewajibkan pemenuhan kebijakan dan pengujian perangkat asli.
