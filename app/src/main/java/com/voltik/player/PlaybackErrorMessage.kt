package com.voltik.player

/** Safe, actionable explanation; never embeds exception messages or content URIs. */
fun playbackErrorMessage(code: String, cause: String?): String {
    val hint = when {
        cause == "SecurityException" || code == "ERROR_CODE_IO_NO_PERMISSION" ->
            "O Android recusou o acesso ao arquivo. Remova e adicione a pasta novamente."
        code == "ERROR_CODE_IO_FILE_NOT_FOUND" ->
            "O arquivo não foi encontrado. Atualize a biblioteca e tente outra faixa."
        code.startsWith("ERROR_CODE_DECODER_") || code.startsWith("ERROR_CODE_DECODING_") ||
            code == "ERROR_CODE_PARSING_CONTAINER_MALFORMED" || code == "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED" ->
            "Não foi possível decodificar esta faixa. Tente outro MP3."
        code.startsWith("ERROR_CODE_AUDIO_TRACK_") ->
            "O Android não conseguiu iniciar a saída de áudio. Tente outro dispositivo de áudio."
        else -> "Não foi possível reproduzir esta música. Tente outra faixa."
    }
    val safeCode = code.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "ERRO_DESCONHECIDO"
    val safeCause = cause?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
    return "$hint\nCódigo: $safeCode" + (safeCause?.let { " ($it)" } ?: "")
}
