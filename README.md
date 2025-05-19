# Smart Parking App

A modern Android application for managing parking spots, bookings, and payments.

## Features

- Google Sign-In authentication
- Real-time parking spot availability
- Interactive maps for parking spot locations
- Booking management system
- Premium subscription features
- Payment integration with Razorpay

## Setup Instructions

1. Clone the repository
2. Create a `strings.xml` file at `app/src/main/res/values/strings.xml` with the following content:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Firebase Configuration -->
    <string name="default_web_client_id">YOUR_FIREBASE_WEB_CLIENT_ID</string>
    
    <!-- Razorpay Configuration -->
    <string name="razorpay_key_id">YOUR_RAZORPAY_KEY_ID</string>
    
    <!-- Google Maps Configuration -->
    <string name="google_maps_key">YOUR_GOOGLE_MAPS_API_KEY</string>
</resources>
```

3. Replace the placeholder values with your actual API keys:
   - `YOUR_FIREBASE_WEB_CLIENT_ID`: Get this from Firebase Console
   - `YOUR_RAZORPAY_KEY_ID`: Get this from Razorpay Dashboard
   - `YOUR_GOOGLE_MAPS_API_KEY`: Get this from Google Cloud Console

4. Build and run the project

## Dependencies

- Firebase Authentication
- Firebase Firestore
- Google Maps SDK
- Razorpay SDK
- Material Design Components

## Security Note

The `strings.xml` file contains sensitive API keys and is intentionally excluded from version control. Make sure to keep your API keys secure and never commit them to the repository.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
