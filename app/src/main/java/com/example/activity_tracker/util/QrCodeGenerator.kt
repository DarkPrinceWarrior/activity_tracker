package com.example.activity_tracker.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Генерирует QR-код как Android Bitmap.
 *
 * Используется на экране регистрации часов — QR содержит JSON
 * с device_id и информацией об устройстве, который сканирует
 * мобильное приложение оператора.
 */
object QrCodeGenerator {

    /**
     * Генерирует QR-код для переданной строки.
     *
     * @param content строка для кодирования (JSON с device_id, model и т.д.)
     * @param size размер bitmap в пикселях (квадрат)
     * @return Bitmap с QR-кодом (чёрный на белом)
     */
    fun generate(content: String, size: Int = 256): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val bitMatrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            hints
        )

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
