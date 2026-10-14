package bw.agriconnect.core.util

import bw.agriconnect.domain.model.UserRole

/**
 * Client-side form validation. Mirrors the backend rules exactly so users get
 * instant inline feedback, while the server remains the authority (the client
 * never decides what is valid).
 */
object FormValidator {

    private val PHONE_REGEX = Regex("^\\+[1-9][0-9]{7,14}$")
    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    const val PASSWORD_MIN_LENGTH = 8

    fun validateFullName(value: String): String? = when {
        value.isBlank() -> "Full name is required."
        value.trim().length < 2 -> "Enter your full name."
        value.trim().length > 120 -> "That name is too long."
        else -> null
    }

    /** Botswana numbers are entered as +267…; we normalise spaces and dashes. */
    fun normalisePhone(value: String): String = value.trim().replace(Regex("[\\s()-]"), "")

    fun validatePhone(value: String): String? {
        val v = normalisePhone(value)
        return when {
            v.isBlank() -> "Phone number is required."
            !v.startsWith("+") -> "Include the country code, for example +26771234567."
            !PHONE_REGEX.matches(v) -> "Enter a valid phone number, for example +26771234567."
            else -> null
        }
    }

    fun validateEmail(value: String, required: Boolean = false): String? = when {
        value.isBlank() -> if (required) "Email address is required." else null
        !EMAIL_REGEX.matches(value.trim()) -> "Enter a valid email address."
        else -> null
    }

    fun validatePassword(value: String): String? = when {
        value.isEmpty() -> "Password is required."
        value.length < PASSWORD_MIN_LENGTH -> "Use at least $PASSWORD_MIN_LENGTH characters."
        !value.any { it.isLetter() } -> "Include at least one letter."
        !value.any { it.isDigit() } -> "Include at least one number."
        value.first().isWhitespace() || value.last().isWhitespace() -> "Remove spaces at the start or end."
        else -> null
    }

    fun validateConfirmPassword(password: String, confirm: String): String? = when {
        confirm.isEmpty() -> "Please confirm your password."
        confirm != password -> "Passwords do not match."
        else -> null
    }

    /** Returns a 0..4 strength score for the password meter. */
    fun passwordStrength(value: String): Int {
        var score = 0
        if (value.length >= PASSWORD_MIN_LENGTH) score++
        if (value.length >= 12) score++
        if (value.any { it.isDigit() } && value.any { it.isLetter() }) score++
        if (value.any { !it.isLetterOrDigit() }) score++
        return score.coerceIn(0, 4)
    }

    fun validateRole(value: UserRole?): String? = if (value == null) "Select your role." else null

    fun requireTrue(value: Boolean, message: String): String? = if (value) null else message

    fun validateRequired(value: String, label: String): String? =
        if (value.isBlank()) "$label is required." else null

    fun validateNonNegative(value: String, label: String): String? {
        if (value.isBlank()) return "$label is required."
        val d = value.toDoubleOrNull() ?: return "$label must be a number."
        return if (d < 0) "$label cannot be negative." else null
    }

    fun validatePositiveInt(value: String, label: String): String? {
        if (value.isBlank()) return "$label is required."
        val i = value.toIntOrNull() ?: return "$label must be a whole number."
        return if (i < 0) "$label cannot be negative." else null
    }
}
