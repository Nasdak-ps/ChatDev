# BLE Uploader (Android)

Aplicativo Android extremamente simples que:

1. Faz varredura BLE enquanto o app está aberto.
2. Conecta ao primeiro dispositivo encontrado.
3. Envia qualquer leitura/notify recebida para um endpoint HTTP.

## Configuração rápida

- Ajuste o endpoint em `app/src/main/res/values/strings.xml` (campo `upload_url`).
- Compile com Android Studio (Gradle).

## Permissões

O app solicita permissões BLE (Android 12+) ou localização (Android 11 ou inferior) na primeira execução.

## Observações

- O envio usa `POST` com `Content-Type: text/plain; charset=utf-8`.
- O payload é o conteúdo BLE em hexadecimal (ex: `0A FF 23`).
