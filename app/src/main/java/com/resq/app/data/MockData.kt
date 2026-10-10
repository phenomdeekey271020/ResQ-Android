package com.resq.app.data

import com.resq.app.model.EmergencyResource
import com.resq.app.model.ResourceType
import com.resq.app.model.SafetyState

/**
 * Local fixture data for the prototype iteration.
 *
 * The emergency flow itself now runs live discovery against the ResQ backend
 * (see com.resq.app.data.remote). These fixtures stay here for development and
 * tests; every fixture is flagged [EmergencyResource.isMock] so the UI always
 * labels them as prototype data and never presents them as live results.
 *
 * There is deliberately no fixture contact list any more: emergency contacts are
 * real, user-configured and stored on the device (see
 * com.resq.app.contacts.EmergencyContactsStore), and a second, fake notion of
 * "contacts" in the fixtures would only mislead a reviewer about what the app
 * actually calls.
 */
object MockData {

    val emergencyResources: List<EmergencyResource> = listOf(
        EmergencyResource(
            type = ResourceType.HOSPITAL,
            name = "St. Mary's General Hospital",
            distanceKm = 2.4,
            detail = "Emergency room · 24/7",
            availability = SafetyState.ACTIVE,
            availabilityLabel = "Open now",
            phone = "555-0142",
            isMock = true,
        ),
        EmergencyResource(
            type = ResourceType.AMBULANCE,
            name = "Cityline Ambulance Service",
            distanceKm = 1.1,
            detail = "Advanced life support unit",
            availability = SafetyState.ACTIVE,
            availabilityLabel = "Available",
            phone = "555-0199",
            isMock = true,
        ),
        EmergencyResource(
            type = ResourceType.BLOOD_BANK,
            name = "Central Blood Bank",
            distanceKm = 3.6,
            detail = "O− / O+ stock reported",
            availability = SafetyState.CAUTION,
            availabilityLabel = "Limited stock",
            phone = "555-0177",
            isMock = true,
        ),
    )
}
