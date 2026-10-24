# Alin's TV

Google TV / Android TV için Türk kanalları oynatıcısı. Yalnızca kanalların kendi resmi, ücretsiz canlı yayınları kullanılır.

- `channels.json`: TV'deki uygulamanın okuduğu kanal listesi. Düzenlenip kaydedildiğinde TV'ler birkaç dakika içinde yeni listeyi alır.
- `bot/`: Her 30 dakikada bir çalışan kanal botu. Kanalların resmi sayfalarından güncel yayın adresini okur ve listeyi günceller.
- `STATUS.md`: Botun son kontrol sonucu.
- `app/`: Android uygulaması.

## Kanal ekleme

`channels.json` içine bir satır eklenir:

```json
{ "id": "ornek", "name": "Örnek TV", "category": "Ulusal", "url": "https://.../playlist.m3u8",
  "headers": { "Referer": "https://www.ornektv.com/" },
  "page": "https://www.ornektv.com/canli-yayin", "pattern": "https://[^\"']+\.m3u8[^\"']*" }
```

`page` ve `pattern` isteğe bağlıdır; verilirse bot adresi bu sayfadan günceller.

## Derleme

```
gradlew assembleRelease -PchannelsUrl=https://raw.githubusercontent.com/<kullanıcı>/tr-kanallar/main/channels.json
```

## Kaynaklar

- Radyo ekranındaki şömine videosu: "Kaminfeuer", Larvis, CC BY-SA 4.0, Wikimedia Commons
  (https://commons.wikimedia.org/wiki/File:Kaminfeuer.webm)
