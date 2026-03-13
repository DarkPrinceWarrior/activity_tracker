# Fix: payload_hash mismatch при отправке пакетов

## Проблема

При отправке пакета на `POST /api/v1/watch/packets` pipeline возвращает ошибку:

```
Payload integrity check failed: the SHA-256 hash of the decoded payload
does not match the hash provided in the request.
```

Хеш, который отправляет мобилка (`payload_hash`), не совпадает с тем, что считает сервер.

---

## Как сервер проверяет хеш

```python
payload_bytes = base64.b64decode(payload_enc)   # 1. Декодирует base64
payload_hash = sha256(payload_bytes).hexdigest() # 2. Считает SHA-256
# 3. Сравнивает с payload_hash из запроса
```

> **Важно:** сервер в текущей MVP-версии **НЕ расшифровывает** данные. Он просто декодирует base64 и хеширует результат.

---

## Что нужно сделать на мобилке

### Правильный алгоритм (Kotlin)

```kotlin
// 1. Собери JSON-payload
val jsonString = """{"schema_version":1,"samples":{"accel":[...]}}"""

// 2. Получи байты (обязательно UTF-8)
val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)

// 3. Закодируй в base64 → это будет payload_enc
val payloadEnc = Base64.encodeToString(jsonBytes, Base64.NO_WRAP)

// 4. Посчитай SHA-256 от jsonBytes (НЕ от payloadEnc!) → это payload_hash
val digest = MessageDigest.getInstance("SHA-256")
val payloadHash = digest.digest(jsonBytes)
    .joinToString("") { "%02x".format(it) }

// 5. Отправь запрос
val request = PacketRequest(
    packet_id = UUID.randomUUID().toString(),
    device_id = deviceId,
    shift_start_ts = shiftStartMs,
    shift_end_ts = shiftEndMs,
    schema_version = 1,
    payload_enc = payloadEnc,       // base64(jsonBytes)
    payload_key_enc = "...",
    iv = "...",
    payload_hash = payloadHash,     // sha256(jsonBytes)
    payload_size_bytes = jsonBytes.size
)
```

### Правильный алгоритм (Swift)

```swift
// 1. JSON → Data (UTF-8)
let jsonData = try JSONSerialization.data(withJSONObject: payload)

// 2. base64 → payload_enc
let payloadEnc = jsonData.base64EncodedString()

// 3. SHA-256 от jsonData → payload_hash
let hash = SHA256.hash(data: jsonData)
let payloadHash = hash.map { String(format: "%02x", $0) }.joined()
```

---

## Частые ошибки

| Ошибка | Почему неправильно |
|--------|--------------------|
| `sha256(payloadEnc)` | Хешируется base64-**строка**, а надо байты **до** base64 |
| `sha256(encryptedBytes)` | Хешируются зашифрованные данные, а сервер не расшифровывает (MVP) |
| Кодировка не UTF-8 | Сервер декодирует base64 и получает другие байты |
| `Base64.DEFAULT` (с переносами строк) | Добавляет `\n`, меняет размер — используй `NO_WRAP` |

---

## Как проверить локально

```bash
# Создай тестовый payload
echo -n '{"schema_version":1,"samples":{}}' > /tmp/payload.json

# Посчитай правильный hash
HASH=$(sha256sum /tmp/payload.json | cut -d' ' -f1)

# Закодируй в base64
PAYLOAD_ENC=$(base64 -w0 /tmp/payload.json)

# Отправь
curl -X POST http://localhost:8000/api/v1/watch/packets \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-packet-001" \
  -H "x-device-id: watch-gw6-001" \
  -d "{
    \"packet_id\": \"test-packet-001\",
    \"device_id\": \"watch-gw6-001\",
    \"shift_start_ts\": $(date -d '-8 hours' +%s%3N),
    \"shift_end_ts\": $(date +%s%3N),
    \"schema_version\": 1,
    \"payload_enc\": \"$PAYLOAD_ENC\",
    \"payload_key_enc\": \"dummy\",
    \"iv\": \"0000000000000000\",
    \"payload_hash\": \"$HASH\",
    \"payload_size_bytes\": $(wc -c < /tmp/payload.json)
  }"
```

---

## TL;DR

```
payload_enc  = base64( jsonBytes )
payload_hash = sha256( jsonBytes ).hex()
```

**`payload_hash`** считается от тех же самых байтов, которые потом кодируются в base64. Не от строки base64, не от зашифрованных данных — от **сырых UTF-8 байтов JSON**.
