package com.messaginglab.generator;

import java.util.List;

/**
 * Plantillas industriales genéricas para empezar rápido. No siguen ningún formato real: se pueden ajustar.
 */
public final class IndustrialTemplates {

	private static final List<TemplatePreset> ALL = List.of(
			new TemplatePreset("robot-ur", "Brazo robótico",
					"Posición de seis articulaciones (en radianes) que se mueven poco a poco, velocidad, modo de seguridad y paro de emergencia, diez veces por segundo.",
					"""
					{
					  "robot": "UR5e-01",
					  "marca_tiempo": "{{ahora}}",
					  "secuencia": {{contador}},
					  "articulaciones_rad": {{ejes(6,-3.14,3.14)}},
					  "velocidad_tcp_mm_s": {{numero(0,250)}},
					  "modo_seguridad": "{{elegir(NORMAL,NORMAL,NORMAL,REDUCIDO)}}",
					  "paro_emergencia": {{booleano(0.02)}}
					}""", Interval.fixed(100), "planta1.robot.ur5e01.estado", false),
			new TemplatePreset("temperatura", "Sensor de temperatura",
					"Temperatura en grados que deriva suavemente entre 18 y 26, una vez por segundo.", """
					{
					  "sensor": "temp-sala-2",
					  "marca_tiempo": "{{ahora}}",
					  "valor_c": {{deriva(22,18,26,0.3)}},
					  "unidad": "C"
					}""", Interval.fixed(1000), "planta1.sensor.temperatura.sala2", false),
			new TemplatePreset("sonometro", "Sonómetro",
					"Nivel de ruido en decibelios entre 35 y 95, dos veces por segundo.", """
					{
					  "sensor": "sonometro-nave-1",
					  "marca_tiempo": "{{ahora}}",
					  "nivel_db": {{deriva(55,35,95,4)}},
					  "ponderacion": "A"
					}""", Interval.fixed(500), "planta1.sensor.ruido.nave1", false),
			new TemplatePreset("vibracion", "Sensor de vibración",
					"Aceleración en tres ejes y valor RMS que oscila, cincuenta veces por segundo.", """
					{
					  "sensor": "vibra-motor-3",
					  "marca_tiempo": "{{ahora}}",
					  "aceleracion_g": {{ejes(3,-2,2)}},
					  "rms": {{senoidal(0.1,1.2,8)}}
					}""", Interval.fixed(20), "planta1.sensor.vibracion.motor3", false),
			new TemplatePreset("plc-tag", "Tag de PLC",
					"Un valor que se consulta cinco veces por segundo y solo se publica cuando cambia, como hace un tag de un PLC.",
					"""
					{
					  "tag": "Cinta1.Marcha",
					  "valor": {{booleano(0.1)}}
					}""", Interval.fixed(200), "planta1.plc.cinta1.marcha", true),
			new TemplatePreset("alarma", "Alarma esporádica",
					"Una alarma de tipo y gravedad aleatorios cada pocos segundos.", """
					{
					  "id": {{contador}},
					  "alarma": "{{elegir(TEMPERATURA_ALTA,PRESION_BAJA,FALLO_SENSOR)}}",
					  "severidad": "{{elegir(AVISO,CRITICA)}}",
					  "marca_tiempo": "{{ahora}}"
					}""", Interval.range(3000, 8000), "planta1.alarma.general", false));

	private IndustrialTemplates() {
	}

	public static List<TemplatePreset> all() {
		return ALL;
	}

}
