# LUNAR VR

**Sistema operacional VR 3DOF para Android** — motor VR próprio (OpenGL ES 3.0),
projetado para VR Box / Cardboard, com **Side-by-Side (SBS)**, rastreamento de
cabeça **3DOF** (giroscópio + quaterniões) e **hand tracking real**
(MediaPipe, 21 landmarks por mão) que alimenta diretamente o sistema de
interação VR.

> **3DOF apenas.** Não há 6DOF, SLAM, ARCore, ARKit nem rastreamento de
> ambiente — a cabeça controla somente a orientação da câmera. Os menus são
> filhos do `WorldRoot` e **nunca** seguem a cabeça.

## Experiência

- **Tela de boot nativa**: checa giroscópio, OpenGL ES 3.0, câmera e modelo de
  hand tracking (recursos ausentes nunca disfarçam — mensagem clara + o sistema
  continua com o que o dispositivo oferece).
- **[ INICIAR MOTOR VR ]**: inicializa o `VRManager` (3DOF → CameraRig →
  WorldRoot → câmeras → SBS → hand tracking → **MainMenu 3D**).
- **Menus espaciais premium** (painéis 3D reais: posição, rotação, escala,
  transparência de vidro, sombra suave, cantos arredondados):
  - **MainMenu** — `LUNAR VR` + `[ INICIAR ] [ APLICATIVOS ] [ CONFIGURAÇÕES ] [ FECHAR ]`
  - **SettingsMenu** — VR (SBS, IPD, sensibilidade/suavização da cabeça,
    calibrar visão, reset de orientação), Hand Tracking (ligar/desligar, mão,
    sensibilidade do pinch, suavização, ray), Gráficos (LOW/MEDIUM/HIGH), Áudio
  - **AppsMenu** — Relógio, Sobre, Sistema, Tutorial
  - **SystemMenu** — status real do motor (3DOF, giroscópio, hand tracking,
    render, FPS, sessão) + sair
  - **AboutMenu / Clock (flutuante) / Tutorial**
  - Botões flutuantes **MENU** e **SISTEMA** fixos no WorldRoot
- **Hand tracking**: até 2 mãos, 21 landmarks, suavização, gestos
  **POINT / OPEN / FIST / PINCH**. Mão virtual 3D translúcida (branca/cinza)
  com juntas, dedos definidos e aura suave.
- **Interação**: `POINT` → ray a partir da ponta do dedo (linha branca/cinza
  com brilho sutil + cursor circular) → **hover** (escala + brilho no botão) →
  `PINCH` (polegar + indicador) → seleção, com feedback visual e sonoro.
  Sliders ajustam em tempo real com o pinch.
- **Fallback**: sem hand tracking (sem câmera/permissão) → **head ray** + toque
  na tela para selecionar. Sem giroscópio → orientação fixa. O SystemMenu
  declara sempre o modo ativo.

## Arquitetura

```
WorldRoot
 ├─ CameraRig → Camera          (3DOF: qSensor → slerp → qCam)
 ├─ MainMenu / SettingsMenu / AppsMenu / SystemMenu / ...
 └─ FloatingPanels (MENU, SISTEMA, relógio)
```

Pacotes (`app/src/main/java/com/lunarvr/`):

| Pacote | Conteúdo |
|---|---|
| `core` | `VRManager` — orquestra init/teardown, 3DOF, CameraRig, render, SBS, IPD, mãos, ray, menus, settings |
| `math` | `Vec3`, `Quat` (Hamilton, slerp), `Mat4` (column-major) + testes unitários |
| `scene` | `SceneNode`, `GLUtil` |
| `stereo` | `StereoRenderer` — FBO offscreen (resolução por preset) + SBS por viewport/scissor |
| `environment` | céu (lat-long + lua), estrelas, piso |
| `tracking` | `HeadTracker` — GAME_ROTATION → ROTATION_VECTOR → giroscópio; correção de roll por gravidade; calibração; suavização slerp; deadzone de micro-oscilação |
| `settings` | `SettingsStore` (SharedPreferences, write-through, `@Volatile` p/ GL thread) |
| `audio` | `SoundBank` — sons de interação sintetizados (WAV em runtime) |
| `hand` | `HandTracker` (CameraX + MediaPipe 0.10.x), `HandFrame`, `HandPose` (profundidade por tamanho aparente, gestos), `VirtualHand` (1 draw call) |
| `interaction` | `Commands`, `InteractionManager` (ray, hover, pinch, slider, head-ray fallback), `RayRenderer` |
| `ui` | `UIItem`, `Icons`, `PanelTexture` (canvas → textura + region upload), `Panel3D` (quad 3D + animação open/close), `MenuController` (todos os menus) |

## Performance

- Poucos draw calls (céu, estrelas, piso, painéis, 2 mãos, rays) e geometrias simples
- Render offscreen em resolução reduzida (LOW 75% / MEDIUM 85% / HIGH 100%)
- Texturas de menu via `glTexSubImage2D` (só a região alterada)
- Mãos em um único buffer dinâmico; presets reduzem subdivisão e aura
- Áudio curto via `SoundPool`; sem assets pesados

## Build

O APK é compilado via **GitHub Actions** (`.github/workflows/build-apk.yml`):
testes unitários + `assembleDebug` → artefato `LunarVR-APK` (`app-debug.apk`).

```
./gradlew testDebugUnitTest assembleDebug   # (requer JDK 17 + Android SDK)
```

- `compileSdk 34` / `minSdk 26` · Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.7
- Permissões: `CAMERA` (hand tracking) e `INTERNET` (download do modelo no
  1º uso, apenas se o asset não estiver no APK — os builds normais o embutem)

## Uso

1. Instale o APK e coloque o celular na VR Box.
2. Toque em **[ INICIAR MOTOR VR ]** (conceda a câmera para hand tracking).
3. Aponte com o dedo indicador → pouse o ray em um botão → **pinch**
   (polegar + indicador) para selecionar.
4. `CONFIGURAÇÕES → CALIBRAR VISÃO` fixa a orientação atual como referência.
5. Botão **voltar** fecha menus; no MainMenu, `[ FECHAR ]` sai do app.
