# OMNIA SPECTRUM

Aplicativo Android experimental para detectar e documentar luz infravermelha próxima que atravessa o conjunto óptico de câmeras convencionais.

**Importante:** não é uma câmera térmica, não mede comprimento de onda e não transforma RGB em espectroscopia calibrada. É um detector diferencial de emissão luminosa com captura Camera2 e controles manuais disponíveis no hardware.

## Funções v0.1

- Prévia em tempo real de câmera frontal/traseira, câmera sob permissão explícita.
- Controles Camera2 para exposição e ISO (quando suportados).
- Posicionamento do alvo por toque; contraste RGB da região central contra anel de fundo.
- Referência OFF, leitura ON e comparação de sinais, com aviso de saturação.
- Fotografias JPEG locais em `Pictures/OMNIA Spectrum` via MediaStore.
- Nenhuma permissão de internet, telemetria, anúncios ou envio de fotos.

## Build

O fluxo GitHub Actions compila o APK de depuração com Android Gradle Plugin 8.9.2 / Gradle 8.11.1 / JDK 17 e publica o APK em um artefato do workflow. A publicação em Releases requer compilação bem-sucedida.

## Limitações científicas

Os canais RGB de JPEG pós-processado não constituem radiometria calibrada. Para quantificação repetível, mantenha enquadramento, iluminação e parâmetros de exposição fixos; confira a saturação e use controles conhecidos de emissão.

Licença: MIT.
