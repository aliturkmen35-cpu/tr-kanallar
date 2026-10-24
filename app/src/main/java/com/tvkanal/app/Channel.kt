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
)
