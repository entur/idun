package org.entur.products.pricing

import java.math.BigDecimal
import java.math.RoundingMode

data class FareRequest(
    val basePrice: BigDecimal,
    val discountPercent: Int,
    val passengers: Int,
)

class FareCalculator {
    fun totalPrice(request: FareRequest): BigDecimal {
        require(request.passengers > 0) { "At least one passenger is required" }
        val discounted = applyDiscount(request.basePrice, request.discountPercent)
        return discounted.multiply(BigDecimal(request.passengers))
    }

    fun pricePerPassenger(request: FareRequest): BigDecimal =
        totalPrice(request).divide(BigDecimal(request.passengers), 2, RoundingMode.HALF_UP)

    private fun applyDiscount(
        basePrice: BigDecimal,
        discountPercent: Int,
    ): BigDecimal {
        val discountFactor =
            BigDecimal(100 - discountPercent)
                .divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
        return basePrice
            .multiply(discountFactor)
            .setScale(2, RoundingMode.HALF_UP)
    }
}
