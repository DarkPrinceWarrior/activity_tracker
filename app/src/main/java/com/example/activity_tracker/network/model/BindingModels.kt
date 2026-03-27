package com.example.activity_tracker.network.model

/**
 * Модели для работы с привязками (bindings).
 * Используются для серверного polling'а: часы проверяют наличие active binding
 * и автоматически начинают/останавливают сбор данных.
 */

/**
 * Ответ от GET /bindings/?device_id=...
 * Пагинированный список привязок.
 */
data class BindingListResponse(
    val items: List<BindingResponse>,
    val total: Int = 0,
    val page: Int = 1,
    val page_size: Int = 20
)

/**
 * Один элемент привязки.
 * Часы интересуются только: id, status, binding_id для пакетов.
 */
data class BindingResponse(
    val id: String,              // UUID привязки (binding_id)
    val device_id: String,
    val employee_id: String,
    val site_id: String? = null,
    val shift_date: String? = null,
    val shift_type: String? = null,
    val bound_at: String? = null,
    val unbound_at: String? = null,
    val status: String           // "active" | "closed"
)
