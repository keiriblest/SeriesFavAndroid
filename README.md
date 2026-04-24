# 📺 SeriesFavAndroid

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-Jetpack-green.svg)](https://developer.android.com/jetpack)
[![MVVM](https://img.shields.io/badge/Architecture-MVVM-blue.svg)](https://developer.android.com/topic/architecture)

SeriesFavAndroid es una aplicación nativa para Android desarrollada con el objetivo de proporcionar una experiencia fluida en la gestión y descubrimiento de series de televisión. Este proyecto sirve como demostración de buenas prácticas en el desarrollo Android moderno, implementando una arquitectura robusta y escalable.

---

## 🚀 Características Técnicas
El núcleo de esta aplicación se basa en los estándares actuales de la industria para garantizar un código limpio, testeable y mantenible:

* **Arquitectura:** MVVM (Model-View-ViewModel) con Clean Architecture.
* **UI Declarativa:** Construida totalmente con [Jetpack Compose](https://developer.android.com/jetpack/compose) para una interfaz reactiva.
* **Gestión de Asincronía:** Uso intensivo de **Kotlin Coroutines** y **Flow** para operaciones no bloqueantes.
* **Networking:** [Retrofit](https://square.github.io/retrofit/) para la comunicación con la API.
* **Persistencia:** [Room Database](https://developer.android.com/training/data-storage/room) para el almacenamiento de datos locales y acceso offline.
* **Inyección de Dependencias:** [Hilt](https://dagger.dev/hilt/) para la gestión de dependencias desacopladas.

---

## 🏗 Arquitectura del Proyecto
El proyecto sigue una separación clara de responsabilidades:

1.  **Data Layer:** Repositorios que abstraen el origen de los datos (Remoto vía API / Local vía Room).
2.  **Domain Layer:** Casos de uso (Use Cases) que encapsulan la lógica de negocio pura.
3.  **Presentation Layer:** ViewModels que exponen estados (StateFlow) a la UI de Compose.

---

## 🛠 Instalación y Configuración

1. **Clonar el repositorio:**
   ```bash
   git clone [https://github.com/keiriblest/SeriesFavAndroid.git](https://github.com/keiriblest/SeriesFavAndroid.git)
