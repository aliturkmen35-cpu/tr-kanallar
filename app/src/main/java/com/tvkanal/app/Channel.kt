package com.tvkanal.app

data class Channel(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Adresi süreli anahtar içeren kanallar için: güncel adres bu sayfadan okunur. */
    val page: String = "",
    /** Sayfada yayın adresini bulan düzenli ifade (boşsa ilk .m3u8 adresi alınır). */
    val pattern: String = "",
    /** "tv" ya da "radio". Radyoda ekranda görüntü yerine radyonun adı gösterilir. */
    val type: String = "tv",
    /** Ana yayın açılmazsa denenecek yedek yayın (ör. ATV için ATV Avrupa). */
    val fallbackUrl: String = "",
    /** Yedek yayının adresi her açılışta bu adresten okunur (ör. kanalın resmi token servisi). */
    val fallbackPage: String = "",
    /** fallbackPage yanıtında yayın adresini bulan düzenli ifade. */
    val fallbackPattern: String = "",
    /** Sayfa/token isteğine eklenen başlıklar (ör. Referer). Yayın isteğine eklenmez. */
    val pageHeaders: Map<String, String> = emptyMap(),
    /** Son yedek: kanalın resmi YouTube canlı yayınının video kimliği. */
    val youtube: String = "",
    /** true ise kanal kendi yayınından vazgeçmez: yedeğe/YouTube'a geçmeden yeniden bağlanır. */
    val stayOriginal: Boolean = false,
) {
    val isRadio get() = type == "radio"
    val hasFallback get() = fallbackUrl.isNotBlank() || fallbackPage.isNotBlank()
}
