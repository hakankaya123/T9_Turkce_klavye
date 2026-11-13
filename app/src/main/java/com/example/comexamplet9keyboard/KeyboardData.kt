package com.example.comexamplet9keyboard

/** Klavyenin sabit verileri (T9Service'ten ayrıldı). */
object KeyboardData {
    // inputType == 0 gelen tarayıcı alanlarını tanımak için paket önekleri
    val BROWSER_PACKAGES = listOf(
        "com.opera.", "com.android.chrome", "com.chrome.", "org.chromium.", "com.google.android.apps.chrome",
        "org.mozilla.", "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
        "com.vivaldi.browser", "com.duckduckgo.mobile.android", "com.kiwibrowser.browser",
        "com.yandex.browser", "com.UCMobile", "com.android.browser"
    )
    val SUFFIXES =
        ("lar ler da de dan den ın in un ün a e ı i u ü ya ye yı yi yu yü nın nin nun nün " +
                "la le ım im um üm sı si su sü ta te tan ten dı di du dü tı ti tu tü mı mi mu mü lı li lu lü " +
                "lık lik luk lük cı ci cu cü çı çi çu çü ki nda nde ndan nden na ne ca ce ça çe sız siz suz süz " +
                "m n k ız iz uz üz ları leri lara lere ların lerin ında inde ımız imiz umuz ümüz yor yorum yorsun " +
                "yoruz dır dir dur dür tır tir ma me mak mek an en ar er ır ir ur ür ı").split(" ")
            .distinct()

    const val EMOJIS = "😀 😁 😂 🤣 😊 😍 😘 😎 🤔 😢 😭 😡 👍 👎 👏 🙏 💪 ❤️ 💔 🔥 ✨ 🎉 🎂 🌹 " +
            "☀️ 🌙 ⭐ 😴 🤗 😅 😉 🙂 🙄 😬 😱 😇 🥰 🤩 😜 🤪 🥳 😏 😒 😞 😔 😟 😤 🤯 " +
            "😳 🥺 😋 🤤 🤭 🤫 🤐 😷 🤒 🤮 😈 👻 💀 🙈 🙉 🙊 🐶 🐱 🐻 🦁 🐸 🐵 🐔 🦋 " +
            "🌸 🌳 🍎 🍕 🍔 🍟 ☕ 🍺 🍷 ⚽ 🏀 🎮 🎵 🎶 📱 💻 📷 🚗 ✈️ 🏠 💰 🎁 📞 " +
            "✅ ❌ ⚠️ 💯 👋 🤝 ✌️ 👌 🤞 👀 💋 💐 🌈 ☔"

    // Ten rengi seçeneği sunulan emojiler
    val SKIN_TONE_BASE_EMOJIS = setOf(
        "👍", "👎", "👏", "🙏", "💪", "👋", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘",
        "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "✍️",
        "🙋", "💁", "🙅", "🙆", "🙇", "🤷", "🤦", "💆", "💇", "🚶", "🏃",
        "🧑", "👦", "👧", "👨", "👩", "🧓", "👴", "👵", "🤝", "🤲", "🦾", "🦿"
    )
}
