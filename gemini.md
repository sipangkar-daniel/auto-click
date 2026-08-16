# Gemini.md - Otak Aplikasi & TODO List: Macro Auto-Click

Dokumen ini mendefinisikan arsitektur sistem, desain komponen, skema data, dan daftar rencana pengerjaan (TODO) untuk aplikasi **Macro Auto-Click** menggunakan Android Native (Kotlin).

---

## 1. Arsitektur Sistem

Aplikasi ini menggunakan **MVVM + Clean Architecture** untuk pemisahan tugas yang jelas, pemeliharaan mudah, dan testabilitas tinggi.

```
┌────────────────────────────────────────────────────────────────────────┐
│                              UI LAYER                                  │
│      [Jetpack Compose UI (MainActivity, MacroListScreen, dsb)]         │
│      [VisualEditorOverlayService (Overlay UI untuk Edit/Playback)]     │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          PRESENTATION LAYER                            │
│           [ViewModels: MacroViewModel, OverlayViewModel]              │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                            DOMAIN LAYER                                │
│       [Models: Macro, MacroStep]                                       │
│       [Repository Interface: MacroRepository]                          │
│       [Use Cases: SaveMacro, GetMacros, RunMacro, DeleteMacro]         │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                              DATA LAYER                                │
│       [Room Database: AppDatabase, MacroDao]                           │
│       [Services: AutoClickAccessibilityService (Gesture Executor)]     │
│       [Image Matcher: OpenCVTemplateMatcher]                          │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Desain Komponen Utama

### A. AutoClickAccessibilityService
*   **Tanggung Jawab**:
    *   Menerima perintah gestur dari Playback Engine dan meluncurkannya menggunakan `dispatchGesture`.
    *   Melakukan screenshot layar (menggunakan `takeScreenshot` API untuk Android 11+).
*   **Metode Utama**:
    *   `performClick(x: Float, y: Float, delayAfter: Long, callback: () -> Unit)`
    *   `performHold(x: Float, y: Float, duration: Long, callback: () -> Unit)`
    *   `performDrag(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long, callback: () -> Unit)`
    *   `captureScreen(callback: (Bitmap?) -> Unit)`

### B. VisualEditorOverlayService
*   **Tanggung Jawab**:
    *   Mengelola overlay mengambang (Floating Control Panel) di atas aplikasi target.
    *   Menggunakan Compose View di dalam `WindowManager` untuk merender Toolbar, marker step, Bounding Box ROI, dan Crop Tool.
*   **Overlay State**:
    *   `IDLE`: Panel kecil mengambang dengan tombol PLAY/EDIT.
    *   `EDITING`: Toolbar lengkap aktif, pengguna bisa menaruh marker Tap, Hold, Drag, Scroll, Image Detection.
    *   `PLAYING`: Toolbar berubah menampilkan status playback (persentase progress, step aktif).

### C. OpenCVTemplateMatcher
*   **Tanggung Jawab**:
    *   Menerima gambar screenshot (Bitmap) dan template (Bitmap).
    *   Melakukan segmentasi ROI (Region of Interest) pada bitmap screenshot sesuai koordinat ROI yang disimpan.
    *   Melakukan Template Matching (`Imgproc.matchTemplate`) menggunakan OpenCV.
    *   Mengembalikan koordinat kecocokan tertinggi dan skor akurasi (similarity).

### D. PlaybackEngine
*   **Tanggung Jawab**:
    *   Menjalankan rangkaian step makro secara berurutan dalam background thread (`Coroutine`).
    *   Menerapkan jeda waktu (Delay) di setiap step.
    *   Mengatur logika kondisional deteksi gambar (Wait/Click).

---

## 3. Skema Database (Room)

### MacroEntity
Menyimpan informasi umum tentang Makro.
*   `id`: Int (AutoGenerate Primary Key)
*   `name`: String
*   `loopCount`: Int
*   `createdAt`: Long

### MacroStepEntity
Menyimpan detail setiap langkah/aksi dalam Makro.
*   `id`: Int (AutoGenerate Primary Key)
*   `macroId`: Int (Foreign Key ke `MacroEntity.id` dengan Cascade Delete)
*   `sequenceOrder`: Int (Urutan eksekusi step)
*   `actionType`: String (`TAP`, `HOLD`, `DRAG`, `SCROLL`, `IMAGE_DETECTION`, `DELAY`)
*   
*   **Parameter Koordinat**:
    *   `startX`: Float? (Untuk Tap, Hold, Drag awal, Scroll awal)
    *   `startY`: Float?
    *   `endX`: Float? (Untuk Drag akhir, Scroll akhir)
    *   `endY`: Float?
*   
*   **Parameter Durasi / Delay**:
    *   `duration`: Long? (Durasi hold/drag dalam ms)
    *   `delayAfter`: Long (Jeda setelah step selesai dalam ms)
*   
*   **Parameter Image Detection**:
    *   `templateImagePath`: String? (Path ke berkas PNG lokal di internal storage)
    *   `roiX`: Int? (Region of Interest X)
    *   `roiY`: Int?
    *   `roiWidth`: Int?
    *   `roiHeight`: Int?
    *   `threshold`: Float? (Akurasi deteksi, default 0.85f)
    *   `timeout`: Long? (Timeout maksimal dalam ms)
    *   `detectionType`: String? (`WAIT_UNTIL_APPEAR`, `WAIT_UNTIL_DISAPPEAR`, `CLICK_ON_APPEAR`)
    *   `timeoutAction`: String? (`STOP`, `SKIP`)
    *   `clickOffset` : String? (Format "x,y" untuk offset klik jika terdeteksi)

---

## 4. DAFTAR TODO DAN RENCANA KERJA

Berikut adalah daftar task yang harus diselesaikan secara bertahap. Setiap penyelesaian 1 TODO wajib disertai dengan commit Git.

- [ ] **TODO 1: Inisialisasi Project Android Native**
    *   Membuat struktur Gradle project (Kotlin, Compose, Room, Dagger Hilt, OpenCV dependency).
    *   Inisialisasi Gradle Wrapper lokal di folder project.
    *   Membuat package structure (`data`, `domain`, `presentation`).
    *   Menyiapkan class `Application` dengan Hilt (`@HiltAndroidApp`).
    *   *Commit message*: `feat: initialize android native project structure, gradle, and dependencies`

- [ ] **TODO 2: Setup Database Room & Data Layer Macro**
    *   Membuat entity Room `MacroEntity` dan `MacroStepEntity`.
    *   Membuat DAO (`MacroDao`) untuk operasi CRUD makro dan step-stepnya.
    *   Membuat `AppDatabase` Room.
    *   Membuat `MacroRepository` interface di `domain` dan implementasinya `MacroRepositoryImpl` di `data`.
    *   Menyiapkan Use Cases dasar (`GetMacrosUseCase`, `SaveMacroUseCase`, `DeleteMacroUseCase`).
    *   *Commit message*: `feat: implement Room database and macro repository data layer`

- [ ] **TODO 3: Implementasi AutoClickAccessibilityService (Gesture Dispatcher)**
    *   Membuat `AutoClickAccessibilityService` yang mewarisi `AccessibilityService`.
    *   Menambahkan file konfigurasi XML `accessibility_service_config.xml` dengan flags dan properti yang dibutuhkan.
    *   Mengimplementasikan fungsi pengiriman gestur (`dispatchGesture`) untuk Tap, Hold, Drag, dan Scroll.
    *   Menambahkan capture screen menggunakan `takeScreenshot` (untuk Android 11+) dan fallback jika diperlukan.
    *   Menambahkan callback status service.
    *   *Commit message*: `feat: implement accessibility service for gesture execution and screenshot`

- [ ] **TODO 4: Setup VisualEditorOverlayService & Floating Window**
    *   Membuat `VisualEditorOverlayService` (Foreground Service) dengan notifikasi status.
    *   Mengimplementasikan window manager overlay untuk merender floating toolbar (Floating Control Panel) di atas semua aplikasi.
    *   Menerapkan Jetpack Compose di dalam overlay window menggunakan `ComposeView`.
    *   Membuat state manager untuk mengatur mode overlay (`IDLE`, `EDITING`, `PLAYING`).
    *   *Commit message*: `feat: implement visual editor overlay service and floating toolbar UI`

- [ ] **TODO 5: Implementasi Visual Action Editor (Marker Drag-and-Drop)**
    *   Membuat UI Composable overlay untuk menampilkan marker visual (lingkaran Tap/Hold hijau/biru, garis panah Drag oranye, panah Scroll ungu) di atas layar game.
    *   Mengimplementasikan logika deteksi sentuhan (touch handling) agar marker bisa digeser (drag & drop) di seluruh layar untuk menentukan posisi koordinat sentuhan secara visual tanpa input angka.
    *   Membuat popup dialog Compose di atas overlay untuk edit konfigurasi delay dan hold duration di setiap step.
    *   *Commit message*: `feat: implement visual action editor with drag-and-drop step markers and delay dialog`

- [ ] **TODO 6: Live Screenshot Capture & Crop Tool (Template & ROI)**
    *   Mengimplementasikan "Ambil Layar Sekarang" dengan memicu `takeScreenshot` dari Accessibility Service.
    *   Merender gambar screenshot sebagai layar freeze yang interaktif.
    *   Membuat overlay Crop Tool (resizable cropping box) untuk mengambil potongan template gambar dari layar.
    *   Menyimpan hasil potongan (crop) bitmap sebagai file PNG secara otomatis ke internal storage.
    *   Membuat overlay Search Area Selector (resizable & draggable bounding box) untuk menentukan koordinat Region of Interest (ROI).
    *   *Commit message*: `feat: implement live capture screenshot, crop template, and ROI selector overlay`

- [ ] **TODO 7: OpenCV Image Detection Matcher**
    *   Inisialisasi OpenCV di `Application.onCreate` menggunakan `OpenCVLoader.initLocal()`.
    *   Mengimplementasikan `OpenCVTemplateMatcher` untuk memotong area ROI di screenshot lalu mencocokkan dengan gambar template menggunakan Template Matching.
    *   Menambahkan pengaturan parameter deteksi: jenis deteksi (`WAIT_UNTIL_APPEAR`, `WAIT_UNTIL_DISAPPEAR`, `CLICK_ON_APPEAR`), threshold akurasi (70-100%), timeout, dan offset klik.
    *   *Commit message*: `feat: implement OpenCV template matching engine and detection parameter settings`

- [ ] **TODO 8: Playback & Macro Execution Engine**
    *   Membuat `PlaybackEngine` untuk mengeksekusi langkah-langkah makro secara berurutan.
    *   Mengimplementasikan eksekusi Tap, Hold, Drag, Scroll melalui Accessibility Service.
    *   Mengimplementasikan logika Image Detection (menunggu gambar muncul/hilang, klik di posisi gambar dengan offset).
    *   Menambahkan visual feedback saat playback (marker berkedip, progress bar di floating panel, status aktif/done).
    *   *Commit message*: `feat: implement macro playback engine with sequential execution and visual feedback`

- [ ] **TODO 9: Main App UI (Jetpack Compose)**
    *   Membuat halaman utama aplikasi dengan daftar makro yang tersimpan.
    *   Menambahkan opsi untuk membuat makro baru, mengedit, menjalankan (Run), atau menghapus makro.
    *   Menambahkan panduan visual langkah demi langkah untuk mengaktifkan izin `Accessibility Service` dan `Overlay Window (SYSTEM_ALERT_WINDOW)`.
    *   Memastikan UI didesain modern, bersih, premium, dan intuitif.
    *   *Commit message*: `feat: complete main application UI with macro management and onboarding guides`
