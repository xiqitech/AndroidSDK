package com.xiqi.sdktest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlin.experimental.or

class Utils {
    companion object {
        fun pdfToImages(context: Context, assetPdfFileName: String): List<Bitmap> {
            val images = mutableListOf<Bitmap>()

            try {
                // 1. 将 assets 中的 PDF 复制到临时文件
                val assetInput = context.assets.open(assetPdfFileName)
                val tempFile = File.createTempFile("temp_pdf", ".pdf", context.cacheDir)
                val output = FileOutputStream(tempFile)

                assetInput.copyTo(output)
                assetInput.close()
                output.close()

                // 2. 用 PdfRenderer 打开临时 PDF 文件
                val fileDescriptor =
                    ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfRenderer = PdfRenderer(fileDescriptor)

                for (i in 0 until pdfRenderer.pageCount) {
                    val page = pdfRenderer.openPage(i)

                    val dpi = 200
                    val scale = dpi / 72f
                    val bitmap =
                        createBitmap((page.width * scale).toInt(), (page.height * scale).toInt())
                    bitmap.eraseColor(Color.WHITE)

                    val matrix = Matrix()
                    matrix.setScale(scale, scale)

                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    images.add(bitmap)

                    page.close()
                }

                pdfRenderer.close()
                fileDescriptor.close()

            } catch (e: Exception) {
                e.printStackTrace()
            }

            return images
        }

        fun scaleAndCropImage(image: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
            // 计算按比例缩放后的高度
            val aspectRatio = image.height.toDouble() / image.width.toDouble()
            val scaledHeight = (targetWidth * aspectRatio).toInt()

            // 创建一个新的缩放后的位图
//            val scaledImage = Bitmap.createScaledBitmap(image, targetWidth, scaledHeight, true)
            val scaledImage =
                createBitmap(targetWidth, scaledHeight)
            val canvas = Canvas(scaledImage)
            val paint = Paint().apply {
                isFilterBitmap = true // 开启位图过滤
                isDither = true // 开启抖动
                isAntiAlias = true
            }
            canvas.drawBitmap(
                image,
                null,
                RectF(0f, 0f, targetWidth.toFloat(), scaledHeight.toFloat()),
                paint
            )

            // 如果缩放后的高度大于目标高度，需要裁剪图像
            return if (targetHeight in 11 until scaledHeight) {
                val yOffset = (scaledHeight - targetHeight) / 2
                Bitmap.createBitmap(scaledImage, 0, yOffset, targetWidth, targetHeight)
            } else {
                scaledImage
            }
        }

        fun grayImage(image: Bitmap): Bitmap {
            val width = image.width
            val height = image.height
            val grayImage = createBitmap(width, height)

            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val pixel = pixels[i]
                val alpha = Color.alpha(pixel)
                if (alpha < 10) {
                    pixels[i] = Color.WHITE
                } else {
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val gray = (0.3 * r + 0.59 * g + 0.11 * b).toInt()
                    pixels[i] = Color.argb(alpha, gray, gray, gray)
                }
            }

            grayImage.setPixels(pixels, 0, width, 0, 0, width, height)
            return grayImage
        }

        fun bitmapToBinaryBuffer(bitmap: Bitmap, sw: Int): ByteBuffer {
            val width = bitmap.width
            val height = bitmap.height
            val totalPixels = width * height

            // 一次性读取所有像素，避免逐点 getPixel
            val pixels = IntArray(totalPixels)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val bufferSize = (totalPixels + 7) / 8 // 向上取整
            val buffer = ByteBuffer.allocate(bufferSize)

            var byte = 0
            var count = 0

            for (i in pixels.indices) {
                val pixel = pixels[i]
                val gray = Color.red(pixel) // R、G、B 一样，只取一个即可

                byte = byte shl 1
                if (gray < sw) {
                    byte = byte or 1
                }

                count++
                if (count == 8) {
                    buffer.put(byte.toByte())
                    byte = 0
                    count = 0
                }
            }

            if (count > 0) {
                byte = byte shl (8 - count)
                buffer.put(byte.toByte())
            }

            buffer.flip()
            return buffer
        }

        fun extractGrayscaleArray(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val total = width * height

            val pixels = IntArray(total)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val grayscale = ByteArray(total)
            for (i in 0 until total) {
                val color = pixels[i]
                grayscale[i] = Color.red(color).toByte()  // 只取红色即可
            }

            return grayscale
        }

        fun applyFloydSteinbergDithering(grayscale: ByteArray, width: Int, height: Int, threshold: Int = 128): BooleanArray {
            val total = width * height
            val dithered = BooleanArray(total)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val oldPixel = grayscale[index].toInt() and 0xFF
                    val newPixel = if (oldPixel < threshold) 0 else 255
                    dithered[index] = newPixel == 0
                    val error = oldPixel - newPixel

                    if (x + 1 < width) {
                        diffuseError(grayscale, index + 1, error, 7)
                    }
                    if (x > 0 && y + 1 < height) {
                        diffuseError(grayscale, index + width - 1, error, 3)
                    }
                    if (y + 1 < height) {
                        diffuseError(grayscale, index + width, error, 5)
                    }
                    if (x + 1 < width && y + 1 < height) {
                        diffuseError(grayscale, index + width + 1, error, 1)
                    }
                }
            }

            return dithered
        }

        // 辅助函数用于传播误差
         fun diffuseError(buffer: ByteArray, index: Int, error: Int, factor: Int) {
            val current = buffer[index].toInt() and 0xFF
            val adjusted = (current + error * factor / 16).coerceIn(0, 255)
            buffer[index] = adjusted.toByte()
        }

         fun booleanArrayToBinaryBuffer(bits: BooleanArray): ByteBuffer {
            val byteSize = (bits.size + 7) / 8
            val buffer = ByteBuffer.allocate(byteSize)

            for (i in bits.indices) {
                if (bits[i]) {
                    val byteIndex = i / 8
                    val bitIndex = 7 - i % 8
                    val current = buffer.get(byteIndex)
                    buffer.put(byteIndex, (current or (1 shl bitIndex).toByte()))
                }
            }

            buffer.position(byteSize)
            buffer.flip()
            return buffer
        }
    }
}