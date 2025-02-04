void flw_shaderLight() {
    #ifdef FLW_EMBEDDED
    FlwLightAo light;
    if (flw_light(flw_vertexPos.xyz, flw_vertexNormal, light)) {
        flw_fragLight = max(flw_fragLight, light.light);

        flw_fragColor.rgb *= light.ao;
    }
    #endif
}
