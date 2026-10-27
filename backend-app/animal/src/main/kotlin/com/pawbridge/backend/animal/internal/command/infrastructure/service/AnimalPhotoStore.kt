package com.pawbridge.backend.animal.internal.command.infrastructure.service

/**
 * Uploaded animal photos as the animal commands see them. Root supplies it only when photo uploads are enabled;
 * without it the commands accept no new photo address and clean nothing up.
 */
interface AnimalPhotoStore {
    /** Whether the address names a photo this service uploaded: `<public base>/animals/<uuid>.<ext>`. */
    fun isUploadedPhoto(url: String): Boolean

    /** Whether the uploaded photo still exists in storage. */
    fun exists(url: String): Boolean

    /** Deletes an uploaded photo. Addresses that are not uploaded photos are left alone. */
    fun delete(url: String)
}
