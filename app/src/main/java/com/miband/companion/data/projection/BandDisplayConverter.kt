package com.miband.companion.data.projection

import android.graphics.Bitmap
import com.miband.companion.data.ble.protocol.MiBandConstants

/**
 * Графический конвертер для адаптации экранного буфера Android к дисплею Mi Smart Band 4.
 */
object BandDisplayConverter {

    private const val TARGET_WIDTH = MiBandConstants.SCREEN_WIDTH   // 120 px
    private const val TARGET_HEIGHT = MiBandConstants.SCREEN_HEIGHT // 240 px
    
    // 120 * 240 / 8 бит = 3600 байт на один полный монохромный кадр
    const val FRAME_BUFFER_SIZE_BYTES = (TARGET_WIDTH * TARGET_HEIGHT) / 8

    /**
     * Преобразует исходный Bitmap произвольного разрешения смартфона в монохромный байтовый массив 120x240.
     * 
     * Алгоритм:
     * 1. Билинейное масштабирование до 120x240.
     * 2. Извлечение яркости пикселей (Luminance ITU-R BT.601): Y = 0.299R + 0.587G + 0.114B.
     * 3. Пространственный дизеринг Флойда-Стейнберга для передачи полутонов и четкости шрифтов.
     * 4. Побитовая упаковка: 8 последовательных пикселей строки пакуются в 1 байт (MSB first).
     * 
     * @param sourceBitmap Исходный кадр с ImageReader (формат ARGB_8888)
     * @return Упакованный массив размером ровно 3600 байт
     */
    fun convertToMonochromeFrame(sourceBitmap: Bitmap): ByteArray {
        val scaledBitmap = if (sourceBitmap.width != TARGET_WIDTH || sourceBitmap.height != TARGET_HEIGHT) {
            Bitmap.createScaledBitmap(sourceBitmap, TARGET_WIDTH, TARGET_HEIGHT, true)
        } else {
            sourceBitmap
        }

        val pixels = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
        scaledBitmap.getPixels(pixels, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)

        if (scaledBitmap != sourceBitmap) {
            scaledBitmap.recycle()
        }

        // Буфер полутонов с плавающей точкой (для распределения ошибок диффузии)
        val gray = FloatArray(TARGET_WIDTH * TARGET_HEIGHT)
        for (i in pixels.indices) {
            val color = pixels[i]
            // Побитовое извлечение цветовых каналов ARGB
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            // Расчет воспринимаемой глазом яркости
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val outputBuffer = ByteArray(FRAME_BUFFER_SIZE_BYTES)
        var byteIndex = 0
        var currentByteAccumulator = 0
        var bitCounter = 0

        // Проход алгоритма Флойда-Стейнберга по строкам и столбцам
        for (y in 0 until TARGET_HEIGHT) {
            for (x in 0 until TARGET_WIDTH) {
                val index = y * TARGET_WIDTH + x
                val oldPixel = gray[index]
                
                // Пороговое бинарное решение: черный (0) или белый (255)
                val newPixel = if (oldPixel >= 128.0f) 255.0f else 0.0f
                val error = oldPixel - newPixel

                // Распределение ошибки квантования на соседние пиксели матрицы:
                //       X   7/16
                // 3/16 5/16 1/16
                if (x + 1 < TARGET_WIDTH) {
                    gray[index + 1] += error * (7.0f / 16.0f)
                }
                if (y + 1 < TARGET_HEIGHT) {
                    if (x - 1 >= 0) {
                        gray[(y + 1) * TARGET_WIDTH + (x - 1)] += error * (3.0f / 16.0f)
                    }
                    gray[(y + 1) * TARGET_WIDTH + x] += error * (5.0f / 16.0f)
                    if (x + 1 < TARGET_WIDTH) {
                        gray[(y + 1) * TARGET_WIDTH + (x + 1)] += error * (1.0f / 16.0f)
                    }
                }

                // Побитовая запись: 1 = белый пиксель, 0 = черный (AMOLED-выключен)
                val bit = if (newPixel == 255.0f) 1 else 0
                currentByteAccumulator = (currentByteAccumulator shl 1) or bit
                bitCounter++

                // Как только накопилось 8 бит — сбрасываем байт в выходной массив
                if (bitCounter == 8) {
                    outputBuffer[byteIndex++] = currentByteAccumulator.toByte()
                    currentByteAccumulator = 0
                    bitCounter = 0
                }
            }
        }

        return outputBuffer
    }
}
