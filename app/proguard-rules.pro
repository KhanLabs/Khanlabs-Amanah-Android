# kotlinx.serialization keeps its generated serializers through its own rules.
# BouncyCastle: only the certificate builder is used; R8 removes the rest.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
