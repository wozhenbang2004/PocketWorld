# Selected UI source attribution and notices

Attribution applies to actual reused source files and their later adaptations. Reference screenshots, videos, marketing material, and brand artwork are not licensed for redistribution merely because a related code repository is open source. Research media is not part of the product UI.

## Provenance distinctions

- Product interface icons use **Solar Icons by 480 Design**, via `@solar-icons/react@2.3.5` maintained by Hakim Saoudi. The icon artwork is licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), with the [original design source](https://www.figma.com/community/file/1166831539721848736); the React adapter is MIT. PocketWorld preserves the packaged geometry, adapts color and optical size, and uses 0.4 secondary opacity for the overlapping team figures. Both upstream license notices are included in the packaged license inventory. The six-bar reasoning-level diagram retains its existing Remix source because its exact geometry encodes six native levels.

- Affect official free components are pinned to `flohoeller/affectui` commit `37b91fa302dea59e28ce1eb2ba7acd7ce23296be` and retain the MIT notice below.
- The library-maintained Affect Pro-preview studies are independent implementations, not an official paid-source distribution. Vendor-free-source MIT notices are not asserted as a license to vendor Pro source. Copied upstream free portions retain their own notices.
- BoardUI old Git source is pinned to `ec5c5eb3e29c2441be63b9422cd54f411ef23466`. Later official free components are preserved in public registry JSON captured on 2026-11-01. Local overlay compatibility changes are identified separately; those files are not claimed byte-identical to the old Git pin.
- The execution-stepper is an independent reference-based implementation. Reference media rights are not incorporated into the code license.
- GrokBot is a separately sourced open-source avatar renderer.
- The press-stretch switch is a library-maintained mechanism reference reproduction. It is not an Animate UI source port, and no upstream license is substituted for that identity.

## Josh W. Comeau tactile button

Source: https://www.joshwcomeau.com/animation/3d-button/

The neutral button is an adaptation of the author's copyable tutorial code. The preserved original source has SHA-256 `338d47d45c87cdb75471da4f0262e71e1d5af2ff624a262b7a537752e9ce2479`. The product applies its own neutral styling and interaction handling.

No separate SPDX license was declared for this tutorial fragment. It is not labeled MIT. Author attribution and the original-to-adapted distinction remain attached to the reused fragment.

## Pinned full fonts

Inter, Noto Sans SC, and JetBrains Mono are obtained unmodified from `google/fonts` commit `6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec`, under SIL Open Font License 1.1. Their exact original OFL texts are retained next to the full font files in `public/fonts/`. `scripts/prepare-fonts.mjs` verifies byte sizes, Git blob identities and required glyph samples, and emits `public/fonts/sources.json` with SHA-256 identities.

## Installed runtime dependencies

`scripts/collect-licenses.mjs` records the actual installed production dependency closure and copies original legal files into `public/licenses/`. It records package versions, package-lock integrity, source metadata and file hashes. Missing license declarations or text are explicitly listed rather than assigned an invented license. Explicit build-source packages can be included when their source is emitted into CSS or assets; unrelated development tools are not collected.

## Textarea intrinsic measurement

The existing Affect composer uses a measurement-only adaptation of
[react-textarea-autosize 8.5.9](https://github.com/Andarist/react-textarea-autosize/tree/ed1894cd8611d99fbea1c47adcf6ee522b1030fd),
pinned to `ed1894cd8611d99fbea1c47adcf6ee522b1030fd`, MIT, Copyright (c) 2013 Andrey Popp.
Exact `getSizingData`, `calculateNodeHeight`, `forceHiddenStyles` sources and the
full MIT license are retained in `src/library/upstream/react-textarea-autosize/`.
The license is included in the packaged source notices. The adapter preserves
the current editor and CSS size limits, owns an inert measuring node per editor,
clears its value after each measurement, and removes it on owner changes. It
does not install the upstream React component or replace the adopted visual UI.

## Text comparison algorithm

The existing Affect-derived file comparison surface uses jsdiff (the pinned
npm package diff@9.0.0) from https://github.com/kpdecker/jsdiff for text
differencing. Its BSD-3-Clause copyright, conditions and disclaimer are copied
from the installed package by the existing runtime-dependency license collector
into the packaged licenses. This algorithm dependency does not replace the
adopted component's source identity or notices.

## Affect UI official free source

Source: https://github.com/flohoeller/affectui/tree/37b91fa302dea59e28ce1eb2ba7acd7ce23296be

```text
MIT License

Copyright (c) 2026 Florian Höller (adfects)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## BoardUI official free source and public registry components

Source: https://github.com/BoardUI/boardui/tree/ec5c5eb3e29c2441be63b9422cd54f411ef23466

```text
MIT License

Copyright (c) 2026 Mertcan Dundar Esmergul (BoardUI)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Vaul 1.1.2

Source: https://github.com/emilkowalski/vaul/tree/3e97aac6a38e4481bade71d7233ed6002e80f9b0

```text
MIT License

Copyright (c) 2023 Emil Kowalski

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Ovio Liquid Tabs

Source: https://github.com/JanaSundar/ovio/tree/81a1bcb6737356038163728b3b2a657c9dcffa32

```text
MIT License

Copyright (c) 2026 Jana Sundar

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## MrLightful / Roman Sirokov TreeView

Source: https://github.com/MrLightful/shadcn-tree-view/tree/41624def7189c141553e7a164c117b44178d5b3a

```text
MIT License

Copyright (c) 2024 Roman Sirokov <roman@mrlightful.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## GrokBot Web

Source: https://github.com/Coiggahou2002/grokbot-web/tree/616c1dd451f105a10a903e01eed8f82370968681

```text
BSD 3-Clause License

Copyright (c) 2026, nasawz
Copyright (c) 2026, grokbot-web contributors
All rights reserved.

This project is a TypeScript / Canvas 2D port of nasawz/GrokBot
(https://github.com/nasawz/GrokBot). The expression, shape and state tables and
the rendering and animation math are derived from that work and remain under
the license below.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## GrokBot Flutter source lineage

Source: https://github.com/nasawz/GrokBot/tree/bb7b634b029a6c99121cb74954020ddfc8c07acf

```text
BSD 3-Clause License

Copyright (c) 2026, nasawz
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Sonner 2.0.8

Source: https://github.com/emilkowalski/sonner/tree/8e4662b39255120b62138312058f5d77c0139a5e

```text
MIT License

Copyright (c) 2023 Emil Kowalski

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```


## react-markdown

Source: https://github.com/remarkjs/react-markdown/tree/44d2e4a44b37461ab7778d6870c1a9eb36393ad2

```text
The MIT License (MIT)

Copyright (c) Espen Hovlandsdal

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## remark-gfm

Source: https://github.com/remarkjs/remark-gfm/tree/109972e8a773bf5dac1d6d2da0776557f36971aa

```text
(The MIT License)

Copyright (c) Titus Wormer <tituswormer@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
'Software'), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Exact-version upstream legal supplements

Nine exact-version legal supplements are retained in `licenses/upstream-supplements.json`: seven Radix packages use the actual MIT text, copyright2022 WorkOS, from official release commit `46235ece78931b198e6908a2cec0b66798d55c79`; each retained package.json proves the corresponding installed version. `@remixicon/react@4.9.0` uses the actual **Remix Icon License v1.0**, copyright2017–2026 Remix Design, from official v4.9.0 commit `019480ec14b97959a1ad35c3de302a224714b068`. It is not relabeled MIT or Apache. Both legal originals match their official Git blob identities.

The collector uses a supplement only for its exact installed package, version and declared license when npm legal text is absent, then verifies retained SHA-256/Git blob hashes and release evidence before copying the original body and source receipt into the packaged notices. The official upstream later provided the MIT license for `react-remove-scroll-bar@2.3.7` at commit `8ca9ba5ea52de03308fe8ced94f7b159a44d28ff`, whose package and source match the release. The npm archive and release tag did not include the license text. `client-only@0.0.1` remains an explicit missing legal-body item in the installed dependency audit.

## OpenMuse phone viewer adaptation

Source: https://github.com/CopilotKit/openmuse/tree/a4944f4c471d3dc93b8bfc060b5da309b5216ab2

Selected phone-viewer presentation and styles were adapted with reference to ComputerEntry, DesktopToolCard, ComputerSheet and shared UI styles. The original source and reference image are no longer retained in this repository. This notice remains for the existing adaptation; it does not describe the PocketWorld-specific Android preview transport as OpenMuse code.

```text
MIT License

Copyright (c) 2026 OpenMuse contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Whaleness / DSH Mobile trajectory

The three-lane overview, turn navigation and event/request/tool detail structure
are adapted from Chaofan Li’s Whaleness iOS `TrajectoryView.swift`:
`Clarklevis1995/dsh-mobile` at `fc12ae44dcb777f9429553048cba21d53849f427`.
The pinned Swift source and license are retained in
`src/library/upstream/whaleness/`; PocketWorld supplies its own native journal,
source-currentness checks, React components and Android presentation.

```text
MIT License

Copyright (c) 2026 Chaofan Li

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```


## UI/UX component additions · 2026-11-06

- BeUI actual source at a866cb2f37ce68c3fc0c66425e86e3f31566e4be (MIT): morphing search/modal, sortable and swipeable list. Exact notice is bundled in source-notices/src/shell-library/upstream/beui-a866cb2/LICENSE.
- ncdai/react-wheel-picker actual source at fbe21d7901b66683ec022337e2140dd815bdb4fd (MIT), with controlled-value and disabled-state adaptations. Exact notice is bundled in source-notices/src/forms-library/upstream/uiux/ncdai-wheel-fbe21d7/LICENSE.
- Ramotion Fluid Slider Swift ValueView at 98c787c0d4cf841213111242ee42897b8542ec6a (MIT) supplies the tracking/return mechanism reference. React Aria/BoardUI provide Web input behavior. Exact notice is bundled in source-notices/src/forms-library/upstream/uiux/RAMOTION-LICENSE. Requested author attribution: https://www.ramotion.com/.
- Ovio Compare Slider source at 81a1bcb6737356038163728b3b2a657c9dcffa32 (MIT), adapted to original attachment pins. Its own source notice is bundled in source-notices/src/workspace-library/upstream/ovio/LICENSE.
- Stageshow hero-grid player source at 8020574 (full identity in WORKSPACE-UIUX-PROVENANCE.json), MIT, Benji Taylor. Its own source notice is bundled in source-notices/src/workspace-library/upstream/stageshow/LICENSE.
- Embla Carousel runtime packages are exactly 8.6.0; exact upstream license and version evidence are retained in licenses/embla-carousel-0fe6583 and incorporated by the dependency collector.
- Reaction action capsules adapt the approved library-maintained independent study v1, retained as src/library/reaction-study-v1.html.source. This is not a released Malay source-code claim. No original screenshots, avatars, reaction counts or participants ship with the application.
- Kevin, Neverland, FlexLayout, Material Carousel and Fennel inform layout/interaction selection. Their marketing screenshots/videos are not shipped as product artwork. Paper Widgets uses the separately identified library study; no paid template source claim is made.

## Glasswork navigation material

Top navigation uses the original `lens.js` and a scoped adaptation of `glass.css`
from [Glasswork](https://github.com/kubai087/glasswork) at commit
`4f0b6744b898b3a9b98fbd913ab24fdb83c53162`, MIT, Copyright (c) 2026 Kubai087.
Original files and exact provenance are retained in
`src/shell-library/upstream/glasswork/`. The complete MIT license and upstream
NOTICE are bundled in `source-notices/src/shell-library/upstream/glasswork/`.
No Apple wallpapers or Playground media are included.
