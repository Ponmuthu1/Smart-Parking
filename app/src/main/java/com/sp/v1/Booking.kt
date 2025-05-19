package com.sp.v1

import com.google.firebase.Timestamp

data class Booking(
    val id: String = "",
    val userId: String = "",
    val parkingSpotId: String = "",
    val parkingSpotName: String = "",
    val startTime: Timestamp = Timestamp.now(),
    val endTime: Timestamp = Timestamp.now(),
    val totalAmount: Double = 0.0,
    val status: String = "pending", // pending, completed, failed, cancelled
    val paymentId: String = "",
    val paymentData: String = "",
    val paymentError: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    var rating: Double? = null,
    var ratingComment: String? = null,
    var ratedAt: Timestamp? = null
) 