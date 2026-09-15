# PiggyBanky

SPA mobile-first per patrimonio, spese, investimenti e budget personalizzabile.

## Installazione sullo smartphone

### PWA

Abilita GitHub Pages sulla repository e apri l’URL Pages da Chrome Android. Dal menu del browser scegli **Installa app** o **Aggiungi alla schermata Home**. I dati restano nel dispositivo tramite `localStorage`.

### APK

Il workflow **Build Android APK** costruisce automaticamente l’app WebView. In GitHub apri l’ultima esecuzione del workflow e scarica l’artifact `finanza-personale-debug-apk`, poi installa l’APK sul telefono Android.

La web app principale è [index.html](./index.html); il wrapper Android usa la stessa interfaccia inclusa in `app/src/main/assets/fire.html`.
