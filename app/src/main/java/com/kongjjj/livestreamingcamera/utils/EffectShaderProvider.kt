package com.kongjjj.livestreamingcamera.utils

import io.github.thibaultbee.streampack.core.elements.processing.video.ShaderProvider

/**
 * 提供鏡頭特效 Shader 的類別
 */
class EffectShaderProvider : ShaderProvider {
    override fun createFragmentShader(samplerVarName: String, fragCoordsVarName: String): String {
        return """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES $samplerVarName;
            varying vec2 $fragCoordsVarName;
            uniform float uAlphaScale;
            
            // 特效開關 (0=關, 1=開)
            uniform int uGrayscale;
            uniform int uBeauty;
            uniform int uBlur;
            uniform int uMosaic;
            uniform int uSepia;
            uniform int uSplitThree;

            // PiP 相關
            uniform int uPipEnabled;
            uniform int uPipRounded;
            uniform samplerExternalOES uPipSampler;
            uniform vec2 uPipPosition; // 左下角座標 [0, 1]
            uniform vec2 uPipSize;     // 寬高 [0, 1]
            uniform vec2 uScreenResolution; // 螢幕解析度

            void main() {
                vec2 uv = $fragCoordsVarName;
                
                // 一開三鏡頭特效 (顯示鏡頭中央 1/3 部分 - 水平重複)
                if (uSplitThree == 1) {
                    float segment = floor(uv.x * 3.0);
                    uv.x = (uv.x * 3.0) - segment; // 將 x 對齊到 [0, 1]
                    uv.x = (uv.x / 3.0) + (1.0 / 3.0); // 映射到原始座標的 [1/3, 2/3] (中央部分)
                }

                vec4 color = texture2D($samplerVarName, uv);
                vec3 finalColor = color.rgb;

                // 0. 馬賽克
                if (uMosaic == 1) {
                    float size = 40.0; 
                    vec2 mosaicCoords = floor($fragCoordsVarName * size) / size;
                    finalColor = texture2D($samplerVarName, mosaicCoords).rgb;
                }

                // 0.1 復古 (Sepia)
                if (uSepia == 1) {
                    float r = finalColor.r;
                    float g = finalColor.g;
                    float b = finalColor.b;
                    finalColor.r = (r * 0.393) + (g * 0.769) + (b * 0.189);
                    finalColor.g = (r * 0.349) + (g * 0.686) + (b * 0.168);
                    finalColor.b = (r * 0.272) + (g * 0.534) + (b * 0.131);
                }

                // 1. 模糊 (9x9 均值模糊)
                if (uBlur == 1) {
                    float offset = 0.002; // 縮小偏移量以適應更多採樣點，避免畫面過於破碎
                    vec3 blurColor = vec3(0.0);
                    for (int i = -4; i <= 4; i++) {
                        for (int j = -4; j <= 4; j++) {
                            blurColor += texture2D($samplerVarName, $fragCoordsVarName + vec2(float(i) * offset, float(j) * offset)).rgb;
                        }
                    }
                    finalColor = blurColor / 81.0;
                }

                // 2. 美顏 (簡單平滑處理 - 這裡使用稍微亮化與減少對比的簡單模擬)
                if (uBeauty == 1) {
                    finalColor = mix(finalColor, vec3(1.0) - (vec3(1.0) - finalColor) * (vec3(1.0) - finalColor), 0.2);
                    finalColor = finalColor * 1.05; // 稍微增亮
                }

                // 3. 黑白
                if (uGrayscale == 1) {
                    float gray = dot(finalColor, vec3(0.299, 0.587, 0.114));
                    finalColor = vec3(gray);
                }

                // 套用 PiP
                if (uPipEnabled == 1) {
                    if (uv.x >= uPipPosition.x && uv.x <= (uPipPosition.x + uPipSize.x) &&
                        uv.y >= uPipPosition.y && uv.y <= (uPipPosition.y + uPipSize.y)) {
                        
                        vec2 pipUv = (uv - uPipPosition) / uPipSize;
                        bool drawPip = true;
                        
                        if (uPipRounded == 1) {
                            // 修正：計算 16px 對應到 PiP 內部座標 [0, 1] 的比例
                            // 假設 PiP 寬高比與螢幕相同，或者以寬度為準
                            float normRadiusX = 16.0 / (uScreenResolution.x * uPipSize.x);
                            float normRadiusY = 16.0 / (uScreenResolution.y * uPipSize.y);
                            
                            vec2 dist = min(pipUv, 1.0 - pipUv);
                            if (dist.x < normRadiusX && dist.y < normRadiusY) {
                                // 由於寬高可能不同，使用橢圓圓心公式
                                vec2 normalizedDist = (vec2(normRadiusX, normRadiusY) - dist) / vec2(normRadiusX, normRadiusY);
                                if (length(normalizedDist) > 1.0) {
                                    drawPip = false;
                                }
                            }
                        }
                        
                        if (drawPip) {
                            finalColor = texture2D(uPipSampler, pipUv).rgb;
                        }
                    }
                }

                gl_FragColor = vec4(finalColor, color.a * uAlphaScale);
            }
        """.trimIndent()
    }
}