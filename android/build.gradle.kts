plugins {
    id("com.android.application") version "9.5.2" apply false
    // AGP 9 compiles Kotlin itself. Naming the Kotlin plugin here, unapplied,
    // only picks which Kotlin compiler it uses.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
