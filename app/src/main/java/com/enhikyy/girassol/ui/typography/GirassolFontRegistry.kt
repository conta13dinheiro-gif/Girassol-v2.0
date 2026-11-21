package com.enhikyy.girassol.ui.typography

import com.enhikyy.girassol.R

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.GoogleFont.Provider
import androidx.compose.ui.text.googlefonts.R as GoogleFontsR
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.girassolTypographyStore by preferencesDataStore(name = "girassol_typography")

enum class FontCategory(val title: String) {
    NEO("Neo-Grotescas"), EDITORIAL("Serifadas Editoriais"), MONO("Monospace & Código"),
    GEOMETRIC("Geométricas & Futuristas"), HUMANIST("Humanistas & Leitura"),
    DISPLAY("Display & Cinema"), ACCESSIBLE("Alta Legibilidade / Acessibilidade")
}
data class FontEntry(val name: String, val category: FontCategory)
data class AiFontPreset(val id: String, val label: String, val interfaceFont: String, val editorialFont: String, val codeFont: String)

object GirassolFontRegistry {
    private val categoryNames: Map<FontCategory, List<String>> = mapOf(
        FontCategory.NEO to listOf("Inter", "Plus Jakarta Sans", "DM Sans", "Work Sans", "Geist", "Geist Mono", "Roboto", "Roboto Flex", "Archivo", "Archivo Narrow", "Barlow", "Barlow Condensed", "Barlow Semi Condensed", "Chivo", "Commissioner", "Encode Sans", "Exo 2", "Fira Sans", "Heebo", "IBM Plex Sans", "Instrument Sans", "Manrope", "Montserrat", "Mulish", "Noto Sans", "Open Sans", "Oswald", "Overpass", "Public Sans", "Red Hat Display", "Red Hat Text", "Roboto Condensed", "Rubik", "Schibsted Grotesk", "Signika", "Source Sans 3", "Ubuntu", "Urbanist", "Varela Round", "Asap", "Assistant", "Atkinson Hyperlegible Next", "Be Vietnam Pro", "Cabin", "Catamaran", "Exo", "IBM Plex Sans Condensed", "Inter Tight", "Josefin Sans", "League Spartan", "Libre Franklin", "News Cycle", "Onest", "PT Sans", "Prompt", "Proza Libre", "Quicksand", "Readex Pro", "Space Grotesk", "Switzer", "Titillium Web", "Ubuntu Sans", "Albert Sans", "Alexandria", "Almarai", "Changa", "Afacad", "Afacad Flux", "Akatab", "Alata", "Alatsi", "Allerta", "Amiko", "Anuphan", "Arima", "Asar", "Asta Sans", "Atma", "Atomic Age", "Autour One", "Average Sans", "Average", "Beiruti", "Bentham", "Biryani", "Blaka", "Blaka Hollow", "Blaka Ink", "Blinker", "Boogaloo", "Brygada 1918", "Buenard", "Bungee Hairline", "Bungee Inline", "Bungee Outline", "Cairo", "Caladea", "Calistoga", "Cambay", "Cantarell", "Cantata One", "Cantora One", "Carme", "Charmonman", "Chathura", "Chau Philomene One", "Cinzel", "Cinzel Decorative", "Clash Display", "Clicker Script", "Concert One", "Content", "Contrail One", "Convergence", "Copse", "Corben", "Courgette", "Crete Round", "Cuprum", "Cutive", "Darker Grotesque", "David Libre", "Deca", "Didact Gothic", "Diphylleia", "Do Hyeon", "Dongle", "Doppio One", "Dorsa", "Dosis", "DotGothic16", "Duru Sans", "Eagle Lake", "Eczar", "El Messiri", "Encode Sans Expanded", "Engagement", "Englebert", "Enriqueta", "Epilogue", "Erica One", "Estonia", "Euphoria Script", "Ewert", "Fahkwang", "Familjen Grotesk", "Fanwood Text", "Farsan", "Fascinate", "Fascinate Inline", "Faster One", "Fasthand", "Federant", "Federo", "Felipa", "Fenix", "Festive", "FigTree", "Finlandica", "Fjalla One", "Fjord One", "Flamenco", "Flavors", "Fondamento", "Forum", "Francois One", "Fredericka the Great", "Fredoka", "Fresca", "Fruktur", "Fugaz One", "Fuggles", "Funnel Display", "Funnel Sans", "GFS Didot", "GFS Neohellenic", "Gafata", "Galdeano", "Gantari", "Gasoek One", "Gemunu Libre", "Genos", "Georama", "Geostar", "Geostar Fill", "Germania One", "Gideon Roman", "Gidugu", "Glegoo", "Gloria Hallelujah", "Gloock", "Gruppo", "Gudea", "Gurajada", "Gwendolyn", "Hahmlet", "Hammersmith One", "Hanalei", "Hanalei Fill", "Handlee", "Hanuman", "Happy Monkey", "Harmattan", "Headland One", "Henny Penny", "Hind", "Hind Madurai", "Hind Siliguri", "Hind Vadodara", "Holtwood One SC", "Homemade Apple", "Hubballi", "Hurricane", "IBM Plex Sans Arabic", "IBM Plex Sans Hebrew", "IBM Plex Sans JP", "IBM Plex Sans KR", "IBM Plex Sans Thai", "Imbue", "Imprima", "Inder", "Indie Flower", "Inika", "Irish Grover", "Italiana", "Italianno", "Itim", "Jacques Francois", "Jacques Francois Shadow", "Jaldi", "Jim Nightshade", "Joan", "Jockey One", "Jomhuria", "Jost", "Julee", "Julius Sans One", "Junge", "Jura", "Just Another Hand", "Just Me Again Down Here", "K2D", "Kadwa", "Kalam", "Kameron", "Kantumruy Pro", "Kaushan Script", "Kavivanar", "Kavoon", "Keania One", "Kelly Slab", "Kenia", "Khand", "Khula", "Kings", "Kodchasan", "Koh Santepheap", "Kotta One", "Koulen", "Kranky", "Kreon", "Kristi", "Krona One", "Kufam", "Kulim Park", "Kumar One", "Kurale", "La Belle Aurore", "Laila", "Lakki Reddy", "Lalezar", "Lancelot", "Lateef", "League Script", "Leckerli One", "Lemon", "Lemonada", "Lexend Zetta", "Libre Barcode 39", "Libre Barcode 39 Extended", "Libre Barcode 39 Text", "Libre Barcode 128", "Libre Barcode EAN13 Text", "Licorice", "Life Savers", "Limelight", "Linden Hill", "Lobster", "Lobster Two", "Londrina Outline", "Londrina Shadow", "Londrina Sketch", "Londrina Solid", "Loved by the King", "Love Ya Like A Sister", "Luckiest Guy", "Macondo", "Macondo Swash Caps", "Magra", "Maiden Orange", "Maitree", "Mako", "Mali", "Mallanna", "Mandali", "Manjari", "Mansalva", "Manuale", "Marcellus", "Marcellus SC", "Marck Script", "Markazi Text", "Marko One", "Marmelad", "Martel Sans", "McLaren", "Mea Culpa", "MedievalSharp", "Medula One", "Meera Inimai", "Megrim", "Meie Script", "Meow Script", "Merienda", "Merriweather Sans", "Metal", "Metamorphous", "Metrophobic", "Micro 5", "Micro 5 Charted", "Milonga", "Miltonian", "Miltonian Tattoo", "Miniver", "Miriam Libre", "Mirza", "Miss Fajardose", "Mitr", "Modak", "Modern Antiqua", "Moderustic", "Mogra", "Mohave", "Monofett", "Monomaniac One", "Montaga", "Montserrat Alternates", "Moo Lah Lah", "Moon Dance", "Moul", "Moulpali", "Mountains of Christmas", "Mouse Memoirs", "Mr Bedfort", "Mr Dafoe", "Mr De Haviland", "Ms Madi", "Mukta", "Mukta Mahee", "Mukta Malar", "Mukta Vaani", "Murecho", "MuseoModerno", "My Soul", "Nabla", "Nanum Brush Script", "Nanum Gothic", "Nanum Myeongjo", "Nanum Pen Script", "Narnoor", "Nerko One", "Neuton", "New Amsterdam", "New Tegomin", "Niconne", "Niramit", "Nixie One", "Nobile", "Nokora", "Norican", "Notable", "Nothing You Could Do", "Noto Color Emoji", "Noto Sans Arabic", "Noto Sans Armenian", "Noto Sans Bengali", "Noto Sans Devanagari", "Noto Sans Georgian", "Noto Sans Hebrew", "Noto Sans JP", "Noto Sans KR", "Noto Sans SC", "Noto Sans TC", "Noto Serif", "Noto Serif JP", "Noto Serif KR", "Noto Serif SC", "Noto Serif TC", "Numans", "Oldenburg", "Oooh Baby", "Oranienbaum", "Original Surfer", "Overlock", "Overlock SC", "Ovo", "PT Sans Narrow", "PT Sans Caption", "Pacifico", "Padauk", "Palanquin", "Palanquin Dark", "Pangolin", "Paprika", "Parisienne", "Passero One", "Pathway Gothic One", "Patrick Hand", "Patua One", "Pavanam", "Paytone One", "Peddana", "Peralta", "Permanent Marker", "Petemoss", "Philosopher", "Piazzolla", "Piedra", "Pinyon Script", "Pirata One", "Plaster", "Playfair Display SC", "Poller One", "Poly", "Pompiere", "Pragati Narrow", "Preahvihear", "Pridi", "Princess Sofia", "Prociono", "Prosto One", "Protest Guerrilla", "Protest Revolution", "Protest Riot", "Protest Strike", "Puppies Play", "Quattrocento Sans", "Quintessential", "Quirky", "Radio Canada", "Radley", "Rakkas", "Raleway", "Raleway Dots", "Ramabhadra", "Ramaraja", "Rambla", "Rampart One", "Ranchers", "Recursive", "Red Rose", "Reem Kufi", "Reem Kufi Fun", "Reenie Beanie", "Reggae One", "Revalia", "Rhodium Libre", "Ribeye", "Ribeye Marrow", "Righteous", "Risque", "Road Rage", "Rock Salt", "Rokkitt", "Romanesco", "Ropa Sans", "Rosario", "Rosarivo", "Rouge Script", "Rubik Dirt", "Rubik Glitch", "Rubik Iso", "Rubik Maps", "Rubik Microbe", "Rubik Moonrocks", "Rubik Puddles", "Rubik Wet Paint", "Ruda", "Rufina", "Ruge Boogie", "Ruluko", "Rum Raisin", "Ruslan Display", "Ruthie", "Rye", "Sacramento", "Sahitya", "Saira", "Saira Condensed", "Saira Extra Condensed", "Saira Semi Condensed", "Saira Stencil One", "Salsa", "Sanchez", "Sancreek", "Sansita", "Sansita Swashed", "Sarabun", "Sarala", "Sarina", "Sarpanch", "Scada", "Schoolbell", "Scope One", "Seaweed Script", "Secular One", "Send Flowers", "Sen", "Sevillana", "Seymour One", "Shadows Into Light", "Shadows Into Light Two", "Shalimar", "Shanti", "Shippori Antique", "Shippori Mincho", "Shizuru", "Shojumaru", "Short Stack", "Siemreap", "Signika Negative", "Simonetta", "Sintony", "Sirin Stencil", "Six Caps", "Slabo 13px", "Slabo 27px", "Slackey", "Smokum", "Smythe", "Sniglet", "Snippet", "Snowburst One", "Sofadi One", "Sofia", "Solitreo", "Solway", "Song Myung", "Sorts Mill Goudy", "Spartan", "Spectral SC", "Spicy Rice", "Spinnaker", "Spirax", "Splash", "Spline Sans", "Spline Sans Mono", "Squada One", "Sree Krushnadevaraya", "Sriracha", "Srisakdi", "Stalemate", "Stalinist One", "Stick", "Stick No Bills", "Stint Ultra Condensed", "Stint Ultra Expanded", "Stoke", "Strait", "Style Script", "Stylish", "Sue Ellen Francisco", "Suez One", "Sunflower", "Supermercado One", "Sura", "Suranna", "Suravaram", "Swanky and Moo Moo", "Tagesschrift", "Tajawal", "Tangerine", "Tapestry", "Tauri", "Taviraj", "Teachers", "Tenali Ramakrishna", "Tenor Sans", "Text Me One", "Thasadith", "The Girl Next Door", "Tienne", "Tillana", "Timmana", "Tinos", "Titan One", "Trocchi", "Trochut", "Trykker", "Tsukimi Rounded", "Twinkle Star", "Uncial Antiqua", "Underdog", "UnifrakturCook", "UnifrakturMaguntia", "Unkempt", "Unlock", "Unna", "Updock", "Vampiro One", "Varela", "Vast Shadow", "Vazirmatn", "Vesper Libre", "Vibes", "Vidaloka", "Viga", "Vina Sans", "Voces", "Volkhov", "Voltaire", "Waiting for the Sunrise", "Walter Turncoat", "Warnes", "Waterfall", "Wellfleet", "Wendy One", "Whisper", "WindSong", "Wire One", "Xanh Mono", "Yanone Kaffeesatz", "Yatra One", "Yesteryear", "Yomogi", "Yrsa", "Yuji Boku", "Yuji Hentaigana Akari", "Yuji Mai", "Yusei Magic", "ZCOOL KuaiLe", "ZCOOL QingKe HuangYou", "ZCOOL XiaoWei", "Zeyada", "Zhi Mang Xing", "Zilla Slab"),
        FontCategory.EDITORIAL to listOf("Newsreader", "Source Serif 4", "Lora", "Instrument Serif", "Fraunces", "Merriweather", "Libre Baskerville", "Spectral", "Crimson Pro", "EB Garamond", "Playfair Display", "DM Serif Display", "DM Serif Text", "PT Serif", "Roboto Slab", "Roboto Serif", "Source Serif 3", "Alegreya", "Alegreya Sans", "Amiri", "Arvo", "Bitter", "Bodoni Moda", "Bree Serif", "Cardo", "Cormorant Garamond", "Cormorant Infant", "Domine", "Esteban", "Frank Ruhl Libre", "Gelasio", "Gentium Book Plus", "Halant", "IBM Plex Serif", "Inknut Antiqua", "Josefin Slab", "Judson", "Libre Caslon Text", "Literata", "Lusitana", "Martel", "Mate", "Noticia Text", "Old Standard TT", "Petrona", "Podkova", "Prata", "PT Serif Caption", "Quando", "Quattrocento", "STIX Two Text", "Trirong", "Vollkorn", "Yeseva One", "Andada Pro", "Antic Didone", "Castoro", "Chonburi", "Cormorant", "Faustina", "Gabriela", "Gotu"),
        FontCategory.MONO to listOf("IBM Plex Mono", "JetBrains Mono", "Fira Code", "Roboto Mono", "Space Mono", "Source Code Pro", "Ubuntu Mono", "Inconsolata", "Anonymous Pro", "Cousine", "DM Mono", "Fragment Mono", "Martian Mono", "Red Hat Mono", "Share Tech Mono", "Victor Mono", "Overpass Mono", "PT Mono", "Major Mono Display", "Nova Mono", "Oxygen Mono", "Syne Mono", "Special Elite", "VT323", "Chivo Mono", "Commit Mono", "Input Mono", "Recursive Mono", "Monaspace Neon", "Monaspace Argon", "Monaspace Krypton", "Monaspace Xenon", "Monaspace Radon", "Azeret Mono", "Cutive Mono", "Courier Prime", "B612 Mono", "Lekton", "M PLUS 1 Code", "Noto Sans Mono", "Sono", "Ubuntu Sans Mono", "Reddit Mono", "Fira Mono", "Nanum Gothic Coding", "M PLUS Code Latin", "Spline Sans Mono"),
        FontCategory.GEOMETRIC to listOf("Outfit", "Figtree", "Urbanist", "Sora", "Syne", "Oxanium", "Orbitron", "Audiowide", "Electrolize", "Chakra Petch", "Michroma", "Tomorrow", "Syncopate", "Unbounded", "Bruno Ace", "Bruno Ace SC", "Zen Dots", "Quantico", "Rajdhani", "Share Tech", "Turret Road", "Wallpoet", "Goldman", "Geo", "Poiret One", "Anybody", "Archivo Black", "Bowlby One SC", "Changa One", "Exo", "Agdasima", "Akshar", "Alumni Sans", "Archivo Narrow", "Arimo", "Barlow Condensed", "Bungee Shade", "Changa", "Chivo", "Days One", "Exo 2", "Iceberg", "Kanit", "League Spartan", "Lexend Mega", "Russo One", "Space Grotesk", "Teko", "Aldrich", "Dela Gothic One", "Zen Tokyo Zoo", "Black Han Sans", "Tilt Neon", "Tilt Prism", "Tilt Warp", "Gugi"),
        FontCategory.HUMANIST to listOf("Atkinson Hyperlegible", "Lexend", "Source Sans Pro", "Arimo", "Golos Text", "Inria Sans", "Karla", "Lato", "Mada", "Nunito", "Overpass", "Andika", "Comfortaa", "Inclusive Sans", "ABeeZee", "Arsenal", "Assistant", "Cabin", "Catamaran", "DM Sans", "Encode Sans", "Heebo", "IBM Plex Sans", "Inter", "Manrope", "Mulish", "Noto Sans", "Open Sans", "PT Sans", "Public Sans", "Red Hat Text", "Roboto", "Signika", "Ubuntu", "Work Sans", "Advent Pro", "Asap", "Barlow", "Be Vietnam Pro", "Commissioner"),
        FontCategory.DISPLAY to listOf("Bebas Neue", "Anton", "League Gothic", "Black Ops One", "Bungee", "Big Shoulders Display", "Staatliches", "Alfa Slab One", "Passion One", "Russo One", "Teko", "Big Shoulders Text", "Days One", "Graduate", "Iceberg", "Kanit", "Lexend Mega", "Lilita One", "Monoton", "Play", "Press Start 2P", "Racing Sans One", "Rowdies", "Rubik Mono One", "Silkscreen", "Stardos Stencil", "Ultra", "Unica One", "Yellowtail", "Trade Winds", "Metal Mania", "Nosifer", "Frijole", "Freckle Face", "New Rocker"),
        FontCategory.ACCESSIBLE to listOf("Atkinson Hyperlegible Next", "Lexend Deca", "Lexend Exa", "Lexend Giga", "Lexend Mega", "Lexend Peta", "Lexend Tera", "OpenDyslexic", "Comic Neue", "Arimo", "Asap", "Assistant", "Cabin", "Comfortaa", "DM Sans", "IBM Plex Sans", "Lato", "Noto Sans", "Nunito Sans", "Open Sans", "PT Sans", "Roboto", "Source Sans 3", "Verdana", "Arial", "Atkinson Hyperlegible", "Inclusive Sans", "Andika", "ABeeZee", "Readex Pro")
    )
    val catalog: List<FontEntry> = categoryNames.flatMap { (category, names) -> names.map { FontEntry(it, category) } }.distinctBy { it.name }
    val presets = listOf(
        AiFontPreset("chatgpt", "ChatGPT", "Inter", "DM Sans", "JetBrains Mono"),
        AiFontPreset("claude", "Claude", "DM Sans", "Newsreader", "IBM Plex Mono"),
        AiFontPreset("perplexity", "Perplexity", "Space Grotesk", "Spectral", "JetBrains Mono"),
        AiFontPreset("gemini", "Gemini", "Outfit", "Lora", "Roboto Mono"),
        AiFontPreset("deepseek", "DeepSeek", "IBM Plex Sans", "Source Serif 4", "JetBrains Mono"),
        AiFontPreset("kimi", "Kimi", "Urbanist", "Atkinson Hyperlegible", "Space Mono")
    )
    val categories = FontCategory.entries
    val embeddedFontResources: Map<String, Int> = mapOf(
        "Inter" to R.font.inter,
        "Plus Jakarta Sans" to R.font.plus_jakarta_sans,
        "DM Sans" to R.font.dm_sans,
        "Work Sans" to R.font.work_sans,
        "Newsreader" to R.font.newsreader,
        "Lora" to R.font.lora,
        "Instrument Serif" to R.font.instrument_serif,
        "Fraunces" to R.font.fraunces,
        "Space Grotesk" to R.font.space_grotesk,
        "Schibsted Grotesk" to R.font.schibsted_grotesk,
        "Libre Baskerville" to R.font.libre_baskerville,
        "Spectral" to R.font.spectral,
        "Archivo" to R.font.archivo,
        "Outfit" to R.font.outfit,
        "Manrope" to R.font.manrope,
        "Figtree" to R.font.figtree,
        "Rubik" to R.font.rubik,
        "Quicksand" to R.font.quicksand,
        "Nunito Sans" to R.font.nunito_sans,
        "IBM Plex Sans" to R.font.ibm_plex_sans,
        "IBM Plex Mono" to R.font.ibm_plex_mono,
        "Fira Code" to R.font.fira_code,
        "Roboto Mono" to R.font.roboto_mono,
        "Urbanist" to R.font.urbanist,
        "Sora" to R.font.sora,
        "Syne" to R.font.syne,
        "Lexend" to R.font.lexend,
        "Atkinson Hyperlegible" to R.font.atkinson_hyperlegible,
        "Red Hat Display" to R.font.red_hat_display,
        "JetBrains Mono" to R.font.jetbrains_mono,
        "Roboto" to R.font.roboto,
        "Noto Sans Arabic" to R.font.noto_sans_arabic
    )
    val embeddedFontCount: Int get() = embeddedFontResources.size
    fun isEmbedded(name: String): Boolean = embeddedFontResources.containsKey(name)
    private val provider = Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = GoogleFontsR.array.com_google_android_gms_fonts_certs
    )
    fun family(name: String): FontFamily = embeddedFontResources[name]?.let { FontFamily(Font(it, weight = FontWeight.Normal)) } ?: FontFamily(Font(googleFont = GoogleFont(name), fontProvider = provider))
    fun names(category: FontCategory): List<String> = categoryNames[category].orEmpty()
    fun preset(id: String): AiFontPreset? = presets.firstOrNull { it.id == id }
    fun isCatalogValid(): Boolean = catalog.size >= 320 && presets.size == 6
}

data class FontSettings(
    val interfaceFont: String = "Inter",
    val chatFont: String = "Inter",
    val editorialFont: String = "Newsreader",
    val telemetryFont: String = "JetBrains Mono",
    val presetId: String = "chatgpt",
    val themeIndex: Int = 0,
    val scale: Float = 1f,
    val lineHeightScale: Float = 1.45f,
    val tabularNumbers: Boolean = true
)

object TypographyPreferences {
    private val interfaceKey = stringPreferencesKey("interface_font")
    private val chatKey = stringPreferencesKey("chat_font")
    private val editorialKey = stringPreferencesKey("editorial_font")
    private val telemetryKey = stringPreferencesKey("telemetry_font")
    private val presetKey = stringPreferencesKey("ai_preset")
    private val themeKey = intPreferencesKey("theme_index")
    private val scaleKey = floatPreferencesKey("font_scale")
    private val lineKey = floatPreferencesKey("line_height_scale")
    private val tabularKey = booleanPreferencesKey("tabular_numbers")

    fun observe(context: Context): Flow<FontSettings> = context.girassolTypographyStore.data.map { p ->
        FontSettings(
            interfaceFont = p[interfaceKey] ?: "Inter",
            chatFont = p[chatKey] ?: "Inter",
            editorialFont = p[editorialKey] ?: "Newsreader",
            telemetryFont = p[telemetryKey] ?: "JetBrains Mono",
            presetId = p[presetKey] ?: "chatgpt",
            themeIndex = (p[themeKey] ?: 0).coerceIn(0, 14),
            scale = (p[scaleKey] ?: 1f).coerceIn(.85f, 1.30f),
            lineHeightScale = (p[lineKey] ?: 1.45f).coerceIn(1.2f, 1.8f),
            tabularNumbers = p[tabularKey] ?: true
        )
    }
    suspend fun update(context: Context, transform: (FontSettings) -> FontSettings) {
        context.girassolTypographyStore.edit { p ->
            val old = FontSettings(
                p[interfaceKey] ?: "Inter", p[chatKey] ?: "Inter",
                p[editorialKey] ?: "Newsreader", p[telemetryKey] ?: "JetBrains Mono",
                p[presetKey] ?: "chatgpt", (p[themeKey] ?: 0).coerceIn(0,14),
                (p[scaleKey] ?: 1f).coerceIn(.85f,1.30f),
                (p[lineKey] ?: 1.45f).coerceIn(1.2f,1.8f), p[tabularKey] ?: true
            )
            val next = transform(old)
            p[interfaceKey] = next.interfaceFont
            p[chatKey] = next.chatFont
            p[editorialKey] = next.editorialFont
            p[telemetryKey] = next.telemetryFont
            p[presetKey] = next.presetId
            p[themeKey] = next.themeIndex.coerceIn(0,14)
            p[scaleKey] = next.scale.coerceIn(.85f,1.30f)
            p[lineKey] = next.lineHeightScale.coerceIn(1.2f,1.8f)
            p[tabularKey] = next.tabularNumbers
        }
    }
}
