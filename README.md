# Diário do Prato

App Android para registar o que comes: tiras uma foto ao prato e tocas nos alimentos
(pão branco ou integral, queijo, fiambre, arroz, massa…). Mostra as calorias do dia,
proteína, hidratos e gordura, e um gráfico dos últimos 7 dias. Funciona sem internet e
os registos ficam só no telemóvel.

## Instalar

1. Abre a página de [Releases](../../releases/latest) no telemóvel.
2. Descarrega o ficheiro `DiarioDoPrato-x.y.z.apk` e abre-o.
3. Se o Android pedir, permite instalar apps desta origem.

## Atualizações

Sempre que há alterações em `main`, o GitHub Actions compila um APK novo e publica-o como
release. A app verifica a última release quando é aberta e mostra um aviso com o botão
**Descarregar**. Instalar por cima mantém os registos.

## Estrutura

- `www/index.html` – a app (HTML, CSS e JavaScript, sem dependências).
- `android/` – projeto Android gerado pelo [Capacitor](https://capacitorjs.com).
- `.github/workflows/release.yml` – compila, assina e publica o APK.

## Compilar localmente

Precisa de Node 20+, JDK 17 e Android SDK 34.

```bash
npm ci
npx cap sync android
cd android && ./gradlew assembleDebug
```

O workflow precisa destes secrets no repositório: `KEYSTORE_B64` (keystore em base64),
`KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Usa sempre a mesma chave, senão o
Android recusa instalar a atualização por cima.
