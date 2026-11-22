package app.amend.messenger.data

import app.amend.messenger.core.json.long
import app.amend.messenger.core.json.str

/** The kinds of animal people can add, with the emoji that stands for them. */
enum class PetKind(val tag: String, val label: String, val emoji: String) {
    CAT("cat", "Cat", "🐱"),
    DOG("dog", "Dog", "🐶"),
    BIRD("bird", "Bird", "🐦"),
    RABBIT("rabbit", "Rabbit", "🐰"),
    FISH("fish", "Fish", "🐟"),
    HAMSTER("hamster", "Hamster", "🐹"),
    TURTLE("turtle", "Turtle", "🐢"),
    COW("cow", "Cow", "🐄"),
    GOAT("goat", "Goat", "🐐"),
    CHICKEN("chicken", "Chicken", "🐔"),
    OTHER("other", "Other", "🐾");

    companion object {
        fun of(tag: String?) = entries.firstOrNull { it.tag == tag } ?: OTHER
    }
}

/** What a care note is about. */
enum class CareTopic(val tag: String, val label: String, val hint: String) {
    FOOD("food", "Food", "What, how much and when. E.g. half a cup of dry food, morning and evening."),
    HEALTH("health", "Health", "Vaccines, medicine, allergies, things to watch for."),
    VET("vet", "Vet", "The vet's name and number, past and next visits."),
    GROOMING("grooming", "Grooming", "Bathing, brushing, nails, cleaning."),
    DAILY("daily", "Daily care", "Walks, play, litter, water, sleep."),
    OTHER("other", "Other", "Anything else someone looking after them should know.");

    companion object {
        fun of(tag: String?) = entries.firstOrNull { it.tag == tag } ?: OTHER
    }
}

data class CareNote(val id: String, val topic: CareTopic, val text: String, val updatedAt: Long) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "topic" to topic.tag, "text" to text, "at" to updatedAt)

    companion object {
        fun fromMap(m: Map<String, Any?>) = CareNote(
            m.str("id") ?: "", CareTopic.of(m.str("topic")), m.str("text") ?: "", m.long("at") ?: 0,
        )
    }
}

/**
 * One of your animals. It stays on this phone (in amend's encrypted files) and is shared
 * only when you choose to, as a text card through your phone's share menu.
 */
data class Pet(
    val id: String,
    val name: String,
    val kind: PetKind,
    val breed: String = "",
    val age: String = "",
    /** What they have: vaccines, microchip, food, toys, a favourite place… */
    val has: String = "",
    val notes: List<CareNote> = emptyList(),
    val createdAt: Long = 0,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "name" to name, "kind" to kind.tag, "breed" to breed, "age" to age, "has" to has,
        "notes" to notes.map { it.toMap() }, "created" to createdAt,
    )

    companion object {
        const val MAX_PETS = 30
        const val MAX_NOTES = 60
        const val MAX_TEXT = 2000

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>) = Pet(
            id = m.str("id") ?: "",
            name = m.str("name") ?: "",
            kind = PetKind.of(m.str("kind")),
            breed = m.str("breed") ?: "",
            age = m.str("age") ?: "",
            has = m.str("has") ?: "",
            notes = (m["notes"] as? List<Any?>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(CareNote::fromMap) },
            createdAt = m.long("created") ?: 0,
        )
    }
}
