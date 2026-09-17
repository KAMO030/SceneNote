package dev.scenenote.models

import kotlinx.serialization.Serializable

@Serializable enum class ModelKind { STREAMING_ASR, OFFLINE_ASR, VAD, SPEAKER, PUNCT, TTS }

/** urls 非空时不走 pack.mirrors（例如 vocoder 只在 GitHub Release）。 */
@Serializable data class ModelFile(val name: String, val bytes: Long, val sha256: String? = null, val urls: List<String>? = null)

/**
 * 模型包描述：文件从 `mirrors[i] + "/" + file.name` 下载（HuggingFace 直链 / hf-mirror.com 镜像 / GitHub Release 单文件）。
 * 不随包内置；用户在「设置 → 模型」按需下载（经 Egress 门面记账，kind = model_asset）。
 * 大小与 sha256 来自 HF API 的 LFS oid（2026-09-17 核实），下载后强校验。
 */
@Serializable data class ModelPack(
    val id: String, val kind: ModelKind, val name: String, val langs: List<String>,
    val files: List<ModelFile>, val mirrors: List<String>, val license: String, val version: String,
    val note: String = "",
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    fun file(prefix: String): ModelFile = files.firstOrNull { it.name.startsWith(prefix) } ?: error("$id 缺少文件 $prefix")
}

/** 内置清单（08 篇技术选型 + I2 调研，见 docs/验收记录/I2.md）。 */
object ModelCatalog {
    private const val HF = "https://huggingface.co/csukuangfj"
    private const val HF_MIRROR = "https://hf-mirror.com/csukuangfj"
    private fun hf(repo: String) = listOf("$HF_MIRROR/$repo/resolve/main", "$HF/$repo/resolve/main")   // 国内先试镜像

    val vadSilero = ModelPack(
        id = "silero-vad", kind = ModelKind.VAD, name = "Silero VAD", langs = listOf("*"),
        files = listOf(ModelFile("silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")),
        mirrors = listOf("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"),
        license = "MIT（上游 snakers4/silero-vad）", version = "v4 导出（k2-fsa）", note = "语音活动检测：切句与待机档；0.6 MB",
    )

    val zipformerZhEn = ModelPack(
        id = "zipformer-zh-en-streaming", kind = ModelKind.STREAMING_ASR, name = "中英流式识别（zipformer int8）", langs = listOf("zh-CN", "en"),
        files = listOf(
            ModelFile("encoder-epoch-99-avg-1.int8.onnx", 181_895_032, "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"),
            ModelFile("decoder-epoch-99-avg-1.int8.onnx", 13_091_040, "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa"),
            ModelFile("joiner-epoch-99-avg-1.int8.onnx", 3_228_404, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"),
            ModelFile("tokens.txt", 56_317),
        ),
        mirrors = hf("sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"),
        license = "Apache-2.0", version = "2023-02-20", note = "快路径流式识别（普通话 / 英文）；189 MB。2026-06 的 X-ASR 中英流式（自带标点）列为 v1.1 评估项",
    )

    val senseVoice = ModelPack(
        id = "sense-voice-zh-en-ja-ko-yue", kind = ModelKind.OFFLINE_ASR, name = "SenseVoice 定稿（中/英/日/韩/粤，int8）", langs = listOf("zh-CN", "en", "ja", "ko", "yue-HK"),
        files = listOf(
            ModelFile("model.int8.onnx", 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            ModelFile("tokens.txt", 315_894),
        ),
        mirrors = hf("sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"),
        license = "FunASR MODEL_LICENSE（非 Apache，上架前法务复核）", version = "2024-07-17", note = "慢路径定稿 + 语种判别（zh/en/yue/ja/ko）；228 MB",
    )

    val paraformerSichuan = ModelPack(
        id = "paraformer-zh-sichuan", kind = ModelKind.OFFLINE_ASR, name = "四川话 / 重庆话识别（Paraformer-large 川渝微调，int8）", langs = listOf("zh-CN-sichuan", "zh-CN"),
        files = listOf(
            ModelFile("model.int8.onnx", 238_429_929, "53813ee1d41722cc6370a571c887e6d0b391d25b8312cf714a31af85ea603812"),
            ModelFile("tokens.txt", 75_756),
        ),
        mirrors = hf("sherpa-onnx-paraformer-zh-int8-2025-10-07"),
        license = "上游 WSChuan-ASR Apache-2.0；底座 Paraformer 为 FunASR MODEL_LICENSE", version = "2025-10-07",
        note = "非流式：川渝单工 M0 按句识别（WSChuan 榜 CER 12.2 vs 原版 14.3）；227 MB",
    )

    val speakerCampp = ModelPack(
        id = "3dspeaker-campplus", kind = ModelKind.SPEAKER, name = "声纹（3D-Speaker CAM++）", langs = listOf("*"),
        files = listOf(ModelFile("3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", 28_281_138, "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11")),
        mirrors = listOf("$HF_MIRROR/speaker-embedding-models/resolve/main", "$HF/speaker-embedding-models/resolve/main", "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"),
        license = "Apache-2.0（ModelScope iic/speech_campplus_sv_zh-cn_16k-common）", version = "2024", note = "判向（我 / 对方）；27 MB",
    )

    /**
     * 端侧 TTS：matcha-icefall-zh-en（中英，16 kHz 与 AudioSink 一致，Apache-2.0）+ vocos 16 kHz vocoder（GitHub Release）。
     * 英文生词经 espeak-ng 音素化，只带 espeak-ng-data 的核心文件 + en/cmn/yue 词典 + lang/（140 个小文件，3 MB；其余语种词典不下）。
     * 不选 MeloTTS / Kokoro：树莓派 4 单线程 RTF 6.7 / 7.6，骁龙 4 系无法实时。sherpa-onnx ≥ 1.12.15 不需要 dict/。
     */
    val ttsMatchaZhEn = ModelPack(
        id = "matcha-tts-zh-en", kind = ModelKind.TTS, name = "中英语音合成（Matcha-TTS + Vocos）", langs = listOf("zh-CN", "en", "zh-CN-sichuan"),
        files = listOf(
            ModelFile("model-steps-3.onnx", 75_717_082, "524286bf6cf11be74329ae1c682ac69e34d6860c2ea9fd1290319d561540b16a"),
            ModelFile("vocos-16khz-univ.onnx", 53_882_848, "b599142a1fb8ff03de3e84ac35ff537c619e56f4267a6fe894851a42844acf9e",
                urls = listOf("https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-16khz-univ.onnx")),
            ModelFile("lexicon.txt", 1_400_278), ModelFile("tokens.txt", 21_146),
            ModelFile("date-zh.fst", 59_154), ModelFile("number-zh.fst", 64_482), ModelFile("phone-zh.fst", 88_630),
        ) + EspeakData.files,
        mirrors = hf("matcha-icefall-zh-en"),
        license = "Apache-2.0（icefall Matcha-TTS；vocos MIT）", version = "2025", note = "端侧语音：vivo 等无系统 TTS 引擎机型的主路径，iOS 作系统语音的兜底；约 135 MB",
    )

    val ttsPacks: List<ModelPack> get() = listOf(ttsMatchaZhEn)

    /** 把 TTS 包翻译成 sherpa 配置（fst 顺序：日期 → 数字 → 电话）。 */
    fun ttsSpec(pack: ModelPack, store: ModelStore, numThreads: Int): dev.scenenote.asr.TtsSpec = when (pack.id) {
        ttsMatchaZhEn.id -> dev.scenenote.asr.TtsSpec(
            kind = dev.scenenote.asr.TtsKind.MATCHA, model = store.path(pack, "model-steps-3"), vocoder = store.path(pack, "vocos"),
            tokens = store.path(pack, "tokens"), lexicon = store.path(pack, "lexicon"), dataDir = store.join(pack, "espeak-ng-data"),
            ruleFsts = listOf("date-zh", "number-zh", "phone-zh").joinToString(",") { store.path(pack, it) }, numThreads = numThreads,
        )
        else -> error("未知 TTS 包 ${pack.id}")
    }

    /** 全部包（顺序 = 建议下载顺序）。粤语专用包（WSYue SenseVoice 2025-09-09 / u2pp conformer）待按需加入。 */
    val all: List<ModelPack> get() = listOf(vadSilero, zipformerZhEn, ttsMatchaZhEn, senseVoice, paraformerSichuan, speakerCampp)
    fun byId(id: String): ModelPack? = all.firstOrNull { it.id == id }
}

/** espeak-ng-data 子集（来自 csukuangfj/matcha-icefall-zh-en，HF tree API 2026-09-17）：核心表 + en / cmn / yue 词典 + lang/。 */
object EspeakData {
    val files: List<ModelFile> = listOf(
        ModelFile("espeak-ng-data/cmn_dict", 1566335, "109aaa7708d3727382acb3ae41d8e2094a7e2bb9f651a81835be22a6f08071fe"),
        ModelFile("espeak-ng-data/en_dict", 166944, "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35"),
        ModelFile("espeak-ng-data/intonations", 2040),
        ModelFile("espeak-ng-data/lang/aav/vi", 111),
        ModelFile("espeak-ng-data/lang/aav/vi-VN-x-central", 143),
        ModelFile("espeak-ng-data/lang/aav/vi-VN-x-south", 142),
        ModelFile("espeak-ng-data/lang/art/eo", 41),
        ModelFile("espeak-ng-data/lang/art/ia", 29),
        ModelFile("espeak-ng-data/lang/art/io", 50),
        ModelFile("espeak-ng-data/lang/art/jbo", 69),
        ModelFile("espeak-ng-data/lang/art/lfn", 135),
        ModelFile("espeak-ng-data/lang/art/piqd", 56),
        ModelFile("espeak-ng-data/lang/art/py", 140),
        ModelFile("espeak-ng-data/lang/art/qdb", 57),
        ModelFile("espeak-ng-data/lang/art/qya", 173),
        ModelFile("espeak-ng-data/lang/art/sjn", 175),
        ModelFile("espeak-ng-data/lang/azc/nci", 114),
        ModelFile("espeak-ng-data/lang/bat/lt", 28),
        ModelFile("espeak-ng-data/lang/bat/ltg", 312),
        ModelFile("espeak-ng-data/lang/bat/lv", 229),
        ModelFile("espeak-ng-data/lang/bnt/sw", 41),
        ModelFile("espeak-ng-data/lang/bnt/tn", 42),
        ModelFile("espeak-ng-data/lang/ccs/ka", 124),
        ModelFile("espeak-ng-data/lang/cel/cy", 37),
        ModelFile("espeak-ng-data/lang/cel/ga", 66),
        ModelFile("espeak-ng-data/lang/cel/gd", 51),
        ModelFile("espeak-ng-data/lang/cus/om", 39),
        ModelFile("espeak-ng-data/lang/dra/kn", 55),
        ModelFile("espeak-ng-data/lang/dra/ml", 57),
        ModelFile("espeak-ng-data/lang/dra/ta", 51),
        ModelFile("espeak-ng-data/lang/dra/te", 70),
        ModelFile("espeak-ng-data/lang/esx/kl", 30),
        ModelFile("espeak-ng-data/lang/eu", 54),
        ModelFile("espeak-ng-data/lang/gmq/da", 43),
        ModelFile("espeak-ng-data/lang/gmq/is", 27),
        ModelFile("espeak-ng-data/lang/gmq/nb", 87),
        ModelFile("espeak-ng-data/lang/gmq/sv", 25),
        ModelFile("espeak-ng-data/lang/gmw/af", 123),
        ModelFile("espeak-ng-data/lang/gmw/de", 42),
        ModelFile("espeak-ng-data/lang/gmw/en", 140),
        ModelFile("espeak-ng-data/lang/gmw/en-029", 335),
        ModelFile("espeak-ng-data/lang/gmw/en-GB-scotland", 295),
        ModelFile("espeak-ng-data/lang/gmw/en-GB-x-gbclan", 238),
        ModelFile("espeak-ng-data/lang/gmw/en-GB-x-gbcwmd", 188),
        ModelFile("espeak-ng-data/lang/gmw/en-GB-x-rp", 249),
        ModelFile("espeak-ng-data/lang/gmw/en-US", 257),
        ModelFile("espeak-ng-data/lang/gmw/en-US-nyc", 271),
        ModelFile("espeak-ng-data/lang/gmw/lb", 31),
        ModelFile("espeak-ng-data/lang/gmw/nl", 23),
        ModelFile("espeak-ng-data/lang/grk/el", 23),
        ModelFile("espeak-ng-data/lang/grk/grc", 99),
        ModelFile("espeak-ng-data/lang/inc/as", 42),
        ModelFile("espeak-ng-data/lang/inc/bn", 25),
        ModelFile("espeak-ng-data/lang/inc/bpy", 39),
        ModelFile("espeak-ng-data/lang/inc/gu", 42),
        ModelFile("espeak-ng-data/lang/inc/hi", 23),
        ModelFile("espeak-ng-data/lang/inc/kok", 26),
        ModelFile("espeak-ng-data/lang/inc/mr", 41),
        ModelFile("espeak-ng-data/lang/inc/ne", 37),
        ModelFile("espeak-ng-data/lang/inc/or", 39),
        ModelFile("espeak-ng-data/lang/inc/pa", 25),
        ModelFile("espeak-ng-data/lang/inc/sd", 66),
        ModelFile("espeak-ng-data/lang/inc/si", 55),
        ModelFile("espeak-ng-data/lang/inc/ur", 94),
        ModelFile("espeak-ng-data/lang/ine/hy", 61),
        ModelFile("espeak-ng-data/lang/ine/hyw", 365),
        ModelFile("espeak-ng-data/lang/ine/sq", 103),
        ModelFile("espeak-ng-data/lang/ira/fa", 90),
        ModelFile("espeak-ng-data/lang/ira/fa-Latn", 269),
        ModelFile("espeak-ng-data/lang/ira/ku", 40),
        ModelFile("espeak-ng-data/lang/iro/chr", 569),
        ModelFile("espeak-ng-data/lang/itc/la", 297),
        ModelFile("espeak-ng-data/lang/jpx/ja", 52),
        ModelFile("espeak-ng-data/lang/ko", 51),
        ModelFile("espeak-ng-data/lang/map/haw", 42),
        ModelFile("espeak-ng-data/lang/miz/mto", 183),
        ModelFile("espeak-ng-data/lang/myn/quc", 210),
        ModelFile("espeak-ng-data/lang/poz/id", 134),
        ModelFile("espeak-ng-data/lang/poz/mi", 367),
        ModelFile("espeak-ng-data/lang/poz/ms", 430),
        ModelFile("espeak-ng-data/lang/qu", 88),
        ModelFile("espeak-ng-data/lang/roa/an", 27),
        ModelFile("espeak-ng-data/lang/roa/ca", 25),
        ModelFile("espeak-ng-data/lang/roa/es", 63),
        ModelFile("espeak-ng-data/lang/roa/es-419", 167),
        ModelFile("espeak-ng-data/lang/roa/fr", 79),
        ModelFile("espeak-ng-data/lang/roa/fr-BE", 84),
        ModelFile("espeak-ng-data/lang/roa/fr-CH", 86),
        ModelFile("espeak-ng-data/lang/roa/ht", 140),
        ModelFile("espeak-ng-data/lang/roa/it", 109),
        ModelFile("espeak-ng-data/lang/roa/pap", 62),
        ModelFile("espeak-ng-data/lang/roa/pt", 95),
        ModelFile("espeak-ng-data/lang/roa/pt-BR", 109),
        ModelFile("espeak-ng-data/lang/roa/ro", 26),
        ModelFile("espeak-ng-data/lang/sai/gn", 47),
        ModelFile("espeak-ng-data/lang/sem/am", 41),
        ModelFile("espeak-ng-data/lang/sem/ar", 50),
        ModelFile("espeak-ng-data/lang/sem/he", 40),
        ModelFile("espeak-ng-data/lang/sem/mt", 41),
        ModelFile("espeak-ng-data/lang/sit/cmn", 686),
        ModelFile("espeak-ng-data/lang/sit/cmn-Latn-pinyin", 161),
        ModelFile("espeak-ng-data/lang/sit/hak", 128),
        ModelFile("espeak-ng-data/lang/sit/my", 56),
        ModelFile("espeak-ng-data/lang/sit/yue", 194),
        ModelFile("espeak-ng-data/lang/sit/yue-Latn-jyutping", 213),
        ModelFile("espeak-ng-data/lang/tai/shn", 92),
        ModelFile("espeak-ng-data/lang/tai/th", 37),
        ModelFile("espeak-ng-data/lang/trk/az", 45),
        ModelFile("espeak-ng-data/lang/trk/ba", 25),
        ModelFile("espeak-ng-data/lang/trk/cv", 40),
        ModelFile("espeak-ng-data/lang/trk/kk", 40),
        ModelFile("espeak-ng-data/lang/trk/ky", 43),
        ModelFile("espeak-ng-data/lang/trk/nog", 39),
        ModelFile("espeak-ng-data/lang/trk/tk", 25),
        ModelFile("espeak-ng-data/lang/trk/tr", 25),
        ModelFile("espeak-ng-data/lang/trk/tt", 23),
        ModelFile("espeak-ng-data/lang/trk/ug", 24),
        ModelFile("espeak-ng-data/lang/trk/uz", 39),
        ModelFile("espeak-ng-data/lang/urj/et", 237),
        ModelFile("espeak-ng-data/lang/urj/fi", 237),
        ModelFile("espeak-ng-data/lang/urj/hu", 73),
        ModelFile("espeak-ng-data/lang/urj/smj", 45),
        ModelFile("espeak-ng-data/lang/zle/be", 52),
        ModelFile("espeak-ng-data/lang/zle/ru", 57),
        ModelFile("espeak-ng-data/lang/zle/ru-LV", 280),
        ModelFile("espeak-ng-data/lang/zle/ru-cl", 91),
        ModelFile("espeak-ng-data/lang/zle/uk", 97),
        ModelFile("espeak-ng-data/lang/zls/bg", 111),
        ModelFile("espeak-ng-data/lang/zls/bs", 230),
        ModelFile("espeak-ng-data/lang/zls/hr", 262),
        ModelFile("espeak-ng-data/lang/zls/mk", 28),
        ModelFile("espeak-ng-data/lang/zls/sl", 43),
        ModelFile("espeak-ng-data/lang/zls/sr", 250),
        ModelFile("espeak-ng-data/lang/zlw/cs", 23),
        ModelFile("espeak-ng-data/lang/zlw/pl", 38),
        ModelFile("espeak-ng-data/lang/zlw/sk", 24),
        ModelFile("espeak-ng-data/phondata", 550424, "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3"),
        ModelFile("espeak-ng-data/phonindex", 39074),
        ModelFile("espeak-ng-data/phontab", 55796),
        ModelFile("espeak-ng-data/yue_dict", 563571, "1d26afa203034698772107abfff1b53acbee30434700a4d2b75dee98951588f8"),
    )
}
