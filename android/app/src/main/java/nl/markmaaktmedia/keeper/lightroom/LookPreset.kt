package nl.markmaaktmedia.keeper.lightroom

/**
 * De camerapresets (filters) van de Find X9 Ultra waarvan Keeper een Lightroom-Look heeft. [oppoName] is de naam in de trailer van een camera-JPG,
 * het bestand `assets/looks/<id>.xml` is de bijbehorende instelling voor in de XMP van een DNG (zie `prototype/maak_looks.py`).
 */
enum class LookPreset(val id: String, val label: String, val oppoName: String) {
    WarmFlash("warm-flash", "Warm flash", "Warm flash"),
    Fresh("fresh", "Fresh", "Fresh"),
    Vintage("vintage", "Vintage", "Vintage");

    companion object {
        /** De presets waarvan er een bestand in de assets zit. */
        fun available(context: android.content.Context): List<LookPreset> {
            val files = context.assets.list("looks")?.toSet().orEmpty()
            return entries.filter { "${it.id}.xml" in files }
        }

        fun of(id: String?): LookPreset? = entries.firstOrNull { it.id == id }
        fun fromOppo(name: String?): LookPreset? = entries.firstOrNull { it.oppoName.equals(name?.trim(), ignoreCase = true) }
    }
}
