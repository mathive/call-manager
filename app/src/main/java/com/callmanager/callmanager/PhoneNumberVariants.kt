package com.callmanager.callmanager

object PhoneNumberVariants {

    fun digitsOnly(raw: String?): String {
        return raw.orEmpty().replace("[^0-9]".toRegex(), "")
    }

    fun toIndianMobileDigits(raw: String?): String? {
        val digits = digitsOnly(raw)
        if (digits.isBlank()) return null

        return when {
            digits.length == 10 && digits.first() in '6'..'9' -> "91$digits"
            digits.length == 11 && digits.startsWith("0") && digits[1] in '6'..'9' -> "91${digits.substring(1)}"
            digits.length == 11 && digits.startsWith("1") && digits[1] in '6'..'9' -> "91${digits.substring(1)}"
            digits.length == 12 && digits.startsWith("91") && digits[2] in '6'..'9' -> digits
            digits.length == 13 && digits.startsWith("1") && digits[1] == '9' && digits[2] == '1' && digits[3] in '6'..'9' -> digits.substring(1)
            digits.length == 13 && digits.startsWith("091") && digits[3] in '6'..'9' -> "91${digits.substring(3)}"
            else -> null
        }
    }

    fun toIndianMobilePlus(raw: String?): String? {
        return toIndianMobileDigits(raw)?.let { "+$it" }
    }

    fun toLocalTenDigits(raw: String?): String? {
        return toIndianMobileDigits(raw)?.substring(2)
    }

    fun isValidIndianMobile(raw: String?): Boolean {
        return toIndianMobileDigits(raw) != null
    }

    fun sameNumber(first: String?, second: String?): Boolean {
        val firstLocal = toLocalTenDigits(first)
        val secondLocal = toLocalTenDigits(second)
        if (!firstLocal.isNullOrBlank() && firstLocal == secondLocal) {
            return true
        }

        val firstDigits = digitsOnly(first).takeLast(10)
        val secondDigits = digitsOnly(second).takeLast(10)
        return firstDigits.isNotBlank() && firstDigits == secondDigits
    }

    fun buildLookupVariants(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        val digits = digitsOnly(trimmed)
        val normalizedDigits = toIndianMobileDigits(trimmed)
        val normalizedPlus = normalizedDigits?.let { "+$it" }
        val localTenDigits = normalizedDigits?.substring(2)

        val variants = linkedSetOf<String>()
        if (trimmed.isNotBlank()) variants += trimmed
        if (digits.isNotBlank()) variants += digits
        if (!normalizedDigits.isNullOrBlank()) variants += normalizedDigits
        if (!normalizedPlus.isNullOrBlank()) variants += normalizedPlus
        if (!localTenDigits.isNullOrBlank()) {
            variants += localTenDigits
            variants += "0$localTenDigits"
        }

        if (trimmed.startsWith("+") && digits.isNotBlank()) {
            variants += "+$digits"
        }

        return variants.toList()
    }

    fun buildFirestoreDocumentIds(raw: String?): List<String> {
        val variants = linkedSetOf<String>()
        buildLookupVariants(raw).forEach { value ->
            if (value.isBlank()) return@forEach
            variants += value
            if (value.startsWith("+")) {
                variants += value.removePrefix("+")
            } else {
                val digits = digitsOnly(value)
                if (digits.isNotBlank()) {
                    variants += digits
                    variants += "+$digits"
                }
            }
        }
        return variants.toList()
    }
}
