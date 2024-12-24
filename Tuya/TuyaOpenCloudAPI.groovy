/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

import com.hubitat.app.exception.UnknownDeviceTypeException
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import hubitat.helper.HexUtils
import hubitat.scheduling.AsyncResponse

/*
 *  Changelog:
 *  10/06/21 - 0.1   - Initial release
 *  10/08/21 - 0.1.1 - added Scene Switch TS004F productKey:xabckq1v
 *  10/09/21 - 0.1.2 - added Scene Switch TS0044 productKey:vp6clf9d; added battery reports (when the virtual driver supports it)
 *  10/10/21 - 0.1.3 - brightness, temperature, humidity, CO2 sensors
 *  10/11/21 - 0.1.4 - door contact, water, smoke, co, pir sensors, fan
 *  10/13/21 - 0.1.5 - fix ternary use error for colors and levels
 *  10/14/21 - 0.1.6 - smart plug, vibration sensor; brightness and temperature sensors scaling bug fix
 *  10/17/21 - 0.1.7 - switched API to use device specification request to get both functions and status ranges
 *  10/25/21 - 0.1.8 - Added support for Window Shade using a custom component driver
 *  10/26/21 - 0.1.9 - Add support for heating devices with custom component driver
 *  10/27/21 - 0.2.0 - Created country to datacenter map (https://developer.tuya.com/en/docs/iot/oem-app-data-center-distributed?id=Kafi0ku9l07qb)
 *  12/02/21 - 0.2.1 - Added support for power strips and triggering Tuya scenes
 *  12/03/21 - 0.2.1 - Added basic support for pet feeder manual feeding button
 *  12/08/21 - 0.2.2 - Added support for additional types of sockets and switches
 *  12/26/21 - 0.2.3 - Added more types of sockets
 *  01/06/22 - 0.2.4 - Added humidifier support (by simon)
 *  01/07/22 - 0.2.5 - Added check for expired tokens
 *  03/09/22 - 0.3.0 - Optimized device state parsing to remove duplicatation
 *  06/04/22 - 0.3.1 - Reduce events by filtering out values that have not changed
 *  07/15/22 - 0.3.2 - When setting level also send power on state
 *                     For covers, support 'situation_set' for fully_open and fully_closed states
 *  08/03/22 - 0.3.3 - Add support for fskg fan speed switch
 *  08/04/22 - 0.3.4 - Fix for set color when level is not set
 *                     Remove ciphercache so encryption key is not cached
 *  09/28/22 - 0.3.5 - Make setColor and setColorTemperature turn on light
 *  01/18/23 - 0.3.6 - Fix error in scene triggering
 *  01/24/23 - 0.3.7 - Support fan_speed_percent level
 *  10/23/24 - 0.3.8 - Update tuya auth request
 *  Custom component drivers located at https://github.com/bradsjm/hubitat-drivers/tree/master/Component
 */

metadata {
    definition(name: 'Tuya IoT Platform (Cloud)', namespace: 'tuya', author: 'Jonathan Bradshaw',
            importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/Tuya/TuyaOpenCloudAPI.groovy') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeDevices'

        attribute 'deviceCount', 'number'
        attribute 'state', 'enum', [
                'not configured',
                'error',
                'authenticating',
                'authenticated',
                'connected',
                'disconnected',
                'ready'
        ]
    }

    preferences {
        section {
            input name: 'access_id',
                    type: 'text',
                    title: 'Tuya API Access/Client Id',
                    required: true

            input name: 'access_key',
                    type: 'password',
                    title: 'Tuya API Access/Client Secret',
                    required: true

            input name: 'appSchema',
                    title: 'Tuya Application',
                    type: 'enum',
                    required: true,
                    defaultValue: 'tuyaSmart',
                    options: [
                            'tuyaSmart': 'Tuya Smart Life App',
                            'smartlife': 'Smart Life App'
                    ]

            input name: 'username',
                    type: 'text',
                    title: 'Tuya Application Login',
                    required: true

            input name: 'password',
                    type: 'password',
                    title: 'Tuya Application Password',
                    required: true

            input name: 'appCountry',
                    title: 'Tuya Application Country',
                    type: 'enum',
                    required: true,
                    defaultValue: 'United States',
                    options: tuyaCountries.country

            input name: 'logEnable',
                    type: 'bool',
                    title: 'Enable debug logging',
                    required: false,
                    defaultValue: true

            input name: 'txtEnable',
                    type: 'bool',
                    title: 'Enable descriptionText logging',
                    required: false,
                    defaultValue: true
        }
    }
}

// Tuya Function Categories
@Field static final Map<String, List<String>> tuyaFunctions = [
        'battery'        : ['battery_percentage', 'va_battery'],
        'basicFlip'      : ['basic_flip'],
        'basicPrivate'   : ['basic_private'],
        'brightness'     : ['bright_value', 'bright_value_v2', 'bright_value_1', 'bright_value_2'],
        'co'             : ['co_state'],
        'co2'            : ['co2_value'],
        'colour'         : ['colour_data', 'colour_data_v2'],
        'contact'        : ['doorcontact_state'],
        'ct'             : ['temp_value', 'temp_value_v2'],
        'control'        : ['control', 'mach_operate'],
        'cruiseMode'     : ['cruise_mode'],
        'cruiseSwitch'   : ['cruise_switch'],
        'decibelSwitch'  : ['decibel_switch'],
        'fanSpeed'       : ['fan_speed_enum', 'fan_speed'],
        'fanSwitch'      : ['switch_fan', 'switch'],
        'floodLight'     : ['floodlight_switch'],
        'motionTracking' : ['motion_tracking'],
        'motionSensitivity': ['motion_sensitivity'],
        'motionSwitch'   : ['motion_switch'],
        'light'          : ['switch_led', 'switch_led_1', 'switch_led_2', 'light'],
        'humiditySet'    : ['dehumidify_set_value'],                                                                                       /* Inserted by SJB */
        'humiditySpeed'  : ['fan_speed_enum'],
        'humidity'       : ['temp_indoor', 'swing', 'shake', 'child_lock', 'lock', 'fan_speed_enum', 'dehumidify_set_value', 'humidity_indoor', 'humidity', 'envhumid', 'switch', 'mode', 'anion', 'pump', 'dry', 'windspeed', 'countdown', 'countdown_left', 'fault'],
        'meteringSwitch' : ['countdown_1', 'add_ele', 'cur_current', 'cur_power', 'cur_voltage', 'relay_status', 'light_mode'],
        'nightvisionMode': ['nightvision_mode'],
        'omniSensor'     : ['bright_value', 'humidity_value', 'va_humidity', 'bright_sensitivity', 'shock_state', 'inactive_state', 'sensitivity'],
        'pir'            : ['pir'],
        'power'          : ['Power', 'power', 'power_go', 'switch', 'switch_1', 'switch_2', 'switch_3', 'switch_4', 'switch_5', 'switch_6', 'switch_usb1', 'switch_usb2', 'switch_usb3', 'switch_usb4', 'switch_usb5', 'switch_usb6', 'alarm_switch', 'start'],
        'powerMeter'    : ['power_total'],
        'energyMeter'   : ['forward_energy_total'],
        'percentControl' : ['percent_control', 'fan_speed_percent', 'position'],
        'push'           : ['manual_feed'],
        'recordSwitch'   : ['record_switch'],
        'sceneSwitch'    : ['switch1_value', 'switch2_value', 'switch3_value', 'switch4_value', 'switch_mode2', 'switch_mode3', 'switch_mode4'],
        'smoke'          : ['smoke_sensor_status'],
        'temperatureSet' : ['temp_set'],
        'temperature'    : ['temp_current', 'va_temperature'],
        'water'          : ['watersensor_state'],
        'workMode'       : ['work_mode'],
        'workState'      : ['work_state'],
        'situationSet'   : ['situation_set'],
        'siren'          : ['siren_switch'],
        'volume'         : ['basic_device_volume'],
        'energy'         : ['forward_energy_total']
].asImmutable()

// Tuya -> Hubitat attributes mappings
// TS004F  productKey:xabckq1v        TS0044 productKey:vp6clf9d
@Field static final Map<String, String> sceneSwitchAction = [
        'single_click': 'pushed',             // TS004F
        'double_click': 'doubleTapped',
        'long_press'  : 'held',
        'click'       : 'pushed',             // TS0044
        'press'       : 'held'
].asImmutable()

@Field static final Map<String, String> sceneSwitchKeyNumbers = [
        'switch_mode2' : '2',                // TS0044
        'switch_mode3' : '3',
        'switch_mode4' : '4',
        'switch1_value': '4',                // '4'for TS004F and '1' for TS0044 !
        'switch2_value': '3',                // TS004F - match the key numbering as in Hubitat built-in TS0044 driver
        'switch3_value': '1',
        'switch4_value': '2',
].asImmutable()

// Constants
@Field static final Integer maxMireds = 370 // 2700K
@Field static final Integer minMireds = 153 // 6536K

// Json Parsing Cache
@Field static final Map<String, Map> jsonCache = new ConcurrentHashMap<>()

// Track for dimming operations
@Field static final Map<String, Integer> levelChanges = new ConcurrentHashMap<>()

// Random number generator
@Field static final Random random = new Random()

// Json Parser
@Field static final JsonSlurper jsonParser = new JsonSlurper()

/*
 * Tuya default attributes used if missing from device details
 */
@Field static final Map defaults = [
        'battery_percentage': [min: 0, max: 100, scale: 0, step: 1, unit: '%', type: 'Integer'],
        'bright_value'      : [min: 0, max: 100, scale: 0, step: 1, type: 'Integer'],
        'bright_value_v2'   : [min: 0, max: 100, scale: 0, step: 1, type: 'Integer'],
        'co2_value'         : [min: 0, max: 1000, scale: 1, step: 1, type: 'Integer'],
        'fan_speed'         : [min: 1, max: 100, scale: 0, step: 1, type: 'Integer'],
        'fan_speed_percent' : [min: 1, max: 100, scale: 0, step: 1, type: 'Integer'],
        'temp_value'        : [min: 0, max: 100, scale: 0, step: 1, type: 'Integer'],
        'temp_value_v2'     : [min: 0, max: 100, scale: 0, step: 1, type: 'Integer'],
        'colour_data'       : [
                h: [min: 1, scale: 0, max: 360, step: 1, type: 'Integer'],
                s: [min: 1, scale: 0, max: 255, step: 1, type: 'Integer'],
                v: [min: 1, scale: 0, max: 255, step: 1, type: 'Integer']
        ],
        'colour_data_v2'    : [
                h: [min: 1, scale: 0, max: 360, step: 1, type: 'Integer'],
                s: [min: 1, scale: 0, max: 1000, step: 1, type: 'Integer'],
                v: [min: 1, scale: 0, max: 1000, step: 1, type: 'Integer']
        ],
        'humidity_value'    : [min: 0, max: 100, scale: 0, step: 1, type: 'Integer'],
        'temp_current'      : [min: -400, max: 2000, scale: 0, step: 1, unit: '°C', type: 'Integer'],
        'temp_set'          : [min: -400, max: 2000, scale: 0, step: 1, unit: '°C', type: 'Integer'],
        'va_humidity'       : [min: 0, max: 1000, scale: 1, step: 1, type: 'Integer'],
        'va_temperature'    : [min: 0, max: 1000, scale: 1, step: 1, type: 'Integer'],
        'manual_feed'       : [min: 1, max: 50, scale: 0, step: 1, type: 'Integer']
].asImmutable()

/**
 *  Tuya Standard Instruction Set Category Mapping to Hubitat Drivers
 *  https://developer.tuya.com/en/docs/iot/standarddescription?id=K9i5ql6waswzq
 */
private static Map mapTuyaCategory(Map d) {
    Map switches = [
            'switch'     : [suffix: 'Switch', driver: 'Generic Component Switch'],
            'switch_1'   : [suffix: 'Socket 1', driver: 'Generic Component Switch'],
            'switch_2'   : [suffix: 'Socket 2', driver: 'Generic Component Switch'],
            'switch_3'   : [suffix: 'Socket 3', driver: 'Generic Component Switch'],
            'switch_4'   : [suffix: 'Socket 4', driver: 'Generic Component Switch'],
            'switch_5'   : [suffix: 'Socket 5', driver: 'Generic Component Switch'],
            'switch_6'   : [suffix: 'Socket 6', driver: 'Generic Component Switch'],
            'switch_usb1': [suffix: 'USB 1', driver: 'Generic Component Switch'],
            'switch_usb2': [suffix: 'USB 2', driver: 'Generic Component Switch'],
            'switch_usb3': [suffix: 'USB 3', driver: 'Generic Component Switch'],
            'switch_usb4': [suffix: 'USB 4', driver: 'Generic Component Switch'],
            'switch_usb5': [suffix: 'USB 5', driver: 'Generic Component Switch'],
            'switch_usb6': [suffix: 'USB 6', driver: 'Generic Component Switch']
    ]

    switch (d.category) {
    // Lighting
        case 'dc':    // String Lights
        case 'dd':    // Strip Lights
        case 'dj':    // Light
        case 'tgq':   // Dimmer Light
        case 'tyndj': // Solar Light
        case 'qjdcz': // Night Light
        case 'xdd':   // Ceiling Light
        case 'ykq':   // Remote Control
            if (getFunctionCode(d.statusSet, tuyaFunctions.colour)) {
                return [driver: 'Generic Component RGBW', devices: switches]
            } else if (getFunctionCode(d.statusSet, tuyaFunctions.ct)) {
                return [driver: 'Generic Component CT', devices: switches]
            } else if (getFunctionCode(d.statusSet, tuyaFunctions.brightness)) {
                return [driver: 'Generic Component Dimmer', devices: switches]
            }
            break
        case 'fsd':  // Ceiling Fan (with Light)
            return [
                    driver : 'Generic Component Fan Control',
                    devices: [
                            'light': [suffix: 'Light', driver: 'Generic Component Switch']
                    ]
            ]

            // Electrical
        case 'tgkg':  // Dimmer Switch
            return [driver: 'Generic Component Dimmer']
        case 'wxkg':  // Scene Switch (TS004F in 'Device trigger' mode only; TS0044)
            return [driver: 'Generic Component Central Scene Switch']
        case 'cl':    // Curtain Motor (uses custom driver)
        case 'clkg':
            return [namespace: 'component', driver: 'Generic Component Window Shade']
        case 'bh':    // Kettle
            return [driver: 'Generic Component Switch']
        case 'cwwsq': // Pet Feeder (https://developer.tuya.com/en/docs/iot/f?id=K9gf468bl11rj)
            return [driver: 'Generic Component Button Controller']
        case 'cz':    // Socket (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
        case 'kg':    // Switch
        case 'pc':    // Power Strip (https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)
            if (getFunctionCode(d.statusSet, tuyaFunctions.colour)) {
                return [driver: 'Generic Component RGBW', devices: switches]
            } else if (getFunctionCode(d.statusSet, tuyaFunctions.brightness)) {
                return [driver: 'Generic Component Dimmer', devices: switches]
            }
            return [devices: switches]
        case 'qccdz': // Automotive charger
            return [namespace: 'component', driver: 'Generic Component Automotive Charger']

            // Security & Sensors
        case 'ms':    // Lock
            return [driver: 'Generic Component Lock']
        case 'ldcg':  // Brightness, temperature, humidity, CO2 sensors
        case 'wsdcg':
        case 'zd':    // Vibration sensor as motion
            return [driver: 'Generic Component Omni Sensor']
        case 'mcs':   // Contact Sensor
            return [driver: 'Generic Component Contact Sensor']
        case 'sj':    // Water Sensor
            return [driver: 'Generic Component Water Sensor']
        case 'ywbj':  // Smoke Detector
            return [driver: 'Generic Component Smoke Detector']
        case 'cobj':  // CO Detector
            return [driver: 'Generic Component Carbon Monoxide Detector']
        case 'co2bj': // CO2 Sensor
            return [driver: 'Generic Component Carbon Dioxide Detector']
        case 'pir':   // Motion Sensor
            return [driver: 'Generic Component Motion Sensor']

            // Large Home Appliances
        case 'rs':    // Heater
            return [namespace: 'component', driver: 'Generic Component Heating Device']

            // Small Home Appliances
        case 'qn':    // Heater
            return [namespace: 'component', driver: 'Generic Component Heating Device']
        case 'cs':    // DeHumidifer
            return [namespace: 'component', driver: 'Generic Component DeHumidifer Device']
        case 'fs':    // Fan
            Map devices = [:]
            if (getFunctionCode(d.statusSet, tuyaFunctions.colour)) {
                devices['light'] = [suffix: 'Light', driver: 'Generic Component RGBW']
            } else if (getFunctionCode(d.statusSet, tuyaFunctions.brightness)) {
                devices['bright_value'] = [suffix: 'Dimmer', driver: 'Generic Component Dimmer']
            } else {
                devices['light'] = [suffix: 'Light', driver: 'Generic Component Switch']
            }

            return [
                    driver : 'Generic Component Fan Control',
                    devices: devices
            ]
        case 'fskg':  // Switch Fan
            return [driver: 'Generic Component Fan Control']
    }

    return [driver: 'Generic Component Switch']
}

/* -------------------------------------------------------
 * Implementation of component commands from child devices
 */

// Component command to close device
void componentClose(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.control)

    if (code == 'mach_operate') {
        if (txtEnable) {
            LOG.info "Closing ${dw}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': 'FZ'])
    } else if (code != null) {
        if (txtEnable) {
            LOG.info "Closing ${dw}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': 'close'])
    } else {
        LOG.error "Unable to determine close function code in ${functions}"
    }
}

// Component command to cycle fan speed
void componentCycleSpeed(DeviceWrapper dw) {
    switch (dw.currentValue('speed')) {
        case 'low':
        case 'medium-low':
            componentSetSpeed(dw, 'medium')
            break
        case 'medium':
        case 'medium-high':
            componentSetSpeed(dw, 'high')
            break
        case 'high':
            componentSetSpeed(dw, 'low')
            break
    }
}

// Component command to lock device
void componentLock(DeviceWrapper dw) {
    LOG.warn "componentLock not yet supported for ${dw}"
}

// Component command to turn on device
void componentOn(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.light + tuyaFunctions.power)

    if (code != null) {
        if (txtEnable) {
            LOG.info "Turning ${dw} on"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': true])
    } else {
        String homeId = dw.getDataValue('homeId')
        String sceneId = dw.getDataValue('sceneId')
        if (sceneId && homeId) {
            if (txtEnable) {
                LOG.info "Triggering ${dw} automation"
            }
            tuyaTriggerScene(homeId, sceneId)
        } else {
            LOG.error "Unable to determine off function code in ${functions}"
        }
    }
}

// Component command to turn off device
void componentOff(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.light + tuyaFunctions.power)

    if (code != null) {
        if (txtEnable) {
            LOG.info "Turning ${dw} off"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': false])
    } else {
        LOG.error "Unable to determine off function code in ${functions}"
    }
}

// Component command to open device
void componentOpen(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.control)

    if (code == 'mach_operate') {
        if (txtEnable) {
            LOG.info "Opening ${dw}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': 'ZZ'])
    } else if (code != null) {
        if (txtEnable) {
            LOG.info "Opening ${dw}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': 'open'])
    } else {
        LOG.error "Unable to determine open function code in ${functions}"
    }
}

// Component command to turn on device
void componentPush(DeviceWrapper dw, BigDecimal button) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.push)

    if (code != null) {
        if (txtEnable) {
            LOG.info "Pushing ${dw} button ${button}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': button])
    } else {
        String homeId = dw.getDataValue('homeId')
        String sceneId = dw.getDataValue('sceneId')
        if (sceneId && homeId) {
            tuyaTriggerScene(homeId, sceneId)
        } else {
            LOG.error "Unable to determine push function code in ${functions}"
        }
    }
}

// Component command to refresh device
void componentRefresh(DeviceWrapper dw) {
    String id = dw.getDataValue('id')
    if (id != null && dw.getDataValue('functions')) {
        LOG.info "Refreshing ${dw} (${id})"
        tuyaGetStateAsync(id)
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': 'opposite', 'value': true])
        // this is for Quoya Smart Curtain, unsure if it affects other devices negatively
    }
}

// Component command to set color
void componentSetColor(DeviceWrapper dw, Map colorMap) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.colour)
    if (code != null) {
        Map color = functions[code] ?: defaults[code]
        // An oddity and workaround for mapping brightness values
        Map bright = getFunction(functions, functions.brightness) ?: color.v
        // level is optional, so fallback to the current device level if the colorMap doesn't include the level
        int level = (colorMap.level != null) ? colorMap.level : dw.currentValue('level')
        Map value = [
                h: remap(colorMap.hue, 0, 100, (int) color.h.min, (int) color.h.max),
                s: remap(colorMap.saturation, 0, 100, (int) color.s.min, (int) color.s.max),
                v: remap(level, 0, 100, (int) bright.min, (int) bright.max)
        ]
        if (txtEnable) {
            LOG.info "Setting ${dw} color to ${colorMap}"
        }
        String power = getFunctionCode(functions, tuyaFunctions.light)
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'),
                ['code': code, 'value': value],
                ['code': power, 'value': true],
                ['code': 'work_mode', 'value': 'colour']
        )
    } else {
        LOG.error "Unable to determine set color function code in ${functions}"
    }
}

// Component command to set color temperature
void componentSetColorTemperature(DeviceWrapper dw, BigDecimal kelvin,
                                  BigDecimal level = null, BigDecimal duration = null) {
    Map<String, Map> functions = getFunctions(dw) << getStatusSet(dw)
    String code = getFunctionCode(functions, tuyaFunctions.ct)
    if (code != null) {
        Map temp = functions[code] ?: defaults[code]
        Integer value = (int) temp.max - Math.ceil(remap(1000000 / kelvin, minMireds, maxMireds, (int) temp.min, (int) temp.max))
        if (txtEnable) {
            LOG.info "Setting ${dw} color temperature to ${kelvin}K"
        }
        String power = getFunctionCode(functions, tuyaFunctions.light)
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'),
                ['code': code, 'value': value],
                ['code': power, 'value': true],
                ['code': 'work_mode', 'value': 'white']
        )
    } else {
        LOG.error "Unable to determine color temperature function code in ${functions}"
    }

    if (level != null && dw.currentValue('level') != level) {
        componentSetLevel(dw, level, duration)
    }
}

// Component command to set effect
void componentSetEffect(DeviceWrapper dw, BigDecimal index) {
    Map value = ['scene_num': index]
    if (txtEnable) {
        LOG.info "Setting ${dw} [work_mode:scene]"
    }
    //if (txtEnable) { LOG.info "Setting ${dw} effects to ${value}" }
    tuyaSendDeviceCommandsAsync(dw.getDataValue('id'),
            ['code': 'work_mode', 'value': 'scene']
            //[ 'code': 'scene_data', 'value': value ], //This Does not Work (but it should...)
    )
}

// Component command to set heating setpoint
void componentSetHeatingSetpoint(DeviceWrapper dw, BigDecimal temperature) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.temperatureSet)
    if (code != null) {
        int value = toCelcius(temperature)
        if (txtEnable) {
            LOG.info "Setting ${dw} heating set point to ${value}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': value])
    } else {
        LOG.error "Unable to determine heating setpoint function code in ${functions}"
    }
}

// Component command to set humidity setpoint
void componentSetHumiditySetpoint(DeviceWrapper dw, BigDecimal humidityNeeded) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.humiditySet)
    if (code != null) {
        if (txtEnable) {
            LOG.info "Setting ${dw} humidity set point to ${humidityNeeded}"
        }
        int setHumidity = humidityNeeded
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': setHumidity])
    }
}

// Component command to set dehumidifier speed
void componentSetHumidifierSpeed(DeviceWrapper dw, BigDecimal speedNeeded) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.humiditySpeed)
    if (code != null) {
        if (txtEnable) {
            LOG.info "Setting ${dw} dehumidifier speed to ${speedNeeded}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': (int) speedNeeded])
    }
}

// Component command to set hue
void componentSetHue(DeviceWrapper dw, BigDecimal hue) {
    componentSetColor(dw, [
            hue       : hue,
            saturation: dw.currentValue('saturation'),
            level     : dw.currentValue('level')
    ])
}

// Component command to set level
/* groovylint-disable-next-line UnusedMethodParameter */

void componentSetLevel(DeviceWrapper dw, BigDecimal level, BigDecimal duration = 0) {
    Map<String, Map> functions = getFunctions(dw)
    String id = dw.getDataValue('id')
    String code = getFunctionCode(functions, tuyaFunctions.percentControl)
    if (code == 'fan_speed_percent') {
        if (level > 0) {
            Map speed = functions[code] ?: defaults[code]
            int min = (speed.min == null) ? 1 : Integer.valueOf(speed.min)
            int max = (speed.max == null) ? 100 : Integer.valueOf(speed.max)
            int speedLevel = remap(level, 0, 100, min, max)
            tuyaSendDeviceCommandsAsync(id, ['code': code, 'value': speedLevel], ['code': 'switch', 'value': true])
        } else {
            tuyaSendDeviceCommandsAsync(id, ['code': 'switch', 'value': false])
        }
    } else {
        String colorMode = dw.currentValue('colorMode') ?: 'CT'
        if (colorMode == 'CT') {
            code = getFunctionCode(functions, tuyaFunctions.brightness)
            String power = getFunctionCode(functions, tuyaFunctions.light + tuyaFunctions.power)
            if (code != null) {
                Map bright = functions[code] ?: defaults[code]
                int value = Math.ceil(remap((int) level, 0, 100, (int) bright.min, (int) bright.max))
                if (txtEnable) {
                    LOG.info "Setting ${dw} level to ${level}%"
                }
                tuyaSendDeviceCommandsAsync(dw.getDataValue('id'),
                        ['code': power, 'value': true],
                        ['code': code, 'value': value]
                )
            } else {
                LOG.error "Unable to determine set level function code in ${functions}"
            }
        } else {
            componentSetColor(dw, [
                    hue       : dw.currentValue('hue'),
                    saturation: dw.currentValue('saturation'),
                    level     : level
            ])
        }
    }
}

void componentSetNextEffect(DeviceWrapper dw) {
    LOG.warn "Set next effect command not supported for ${dw}"
}

void componentSetPreviousEffect(DeviceWrapper dw) {
    LOG.warn "Set previous effect command not supported for ${dw}"
}

// Component command to set position
void componentSetPosition(DeviceWrapper dw, BigDecimal position) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.percentControl)

    if (code != null) {
        if (txtEnable) {
            LOG.info "Setting ${dw} position to ${position}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': (int) position])
    } else {
        LOG.error "Unable to determine set position function code in ${functions}"
    }
}

// Component command to set saturation
void componentSetSaturation(DeviceWrapper dw, BigDecimal saturation) {
    componentSetColor(dw, [
            hue       : dw.currentValue('hue'),
            saturation: saturation,
            level     : dw.currentValue('level')
    ])
}

// Component command to set fan speed
void componentSetSpeed(DeviceWrapper dw, String speed) {
    Map<String, Map> functions = getFunctions(dw)
    String fanSpeedCode = getFunctionCode(functions, tuyaFunctions.fanSpeed)
    String fanSpeedPercent = getFunctionCode(functions, tuyaFunctions.percentControl)
    String fanSwitchCode = getFunctionCode(functions, tuyaFunctions.fanSwitch)
    String id = dw.getDataValue('id')
    if (txtEnable) {
        LOG.info "Setting speed to ${speed}"
    }
    switch (speed) {
        case 'on':
            tuyaSendDeviceCommandsAsync(id, ['code': fanSwitchCode, 'value': true])
            break
        case 'off':
            tuyaSendDeviceCommandsAsync(id, ['code': fanSwitchCode, 'value': false])
            break
        case 'auto':
            LOG.warn 'Speed level auto is not supported'
            break
        default:
            if (fanSpeedCode != null) {
                Map speedFunc = functions[fanSpeedCode] ?: defaults[fanSpeedCode]
                int speedVal = ['low', 'medium-low', 'medium', 'medium-high', 'high'].indexOf(speed)
                String value
                switch (speedFunc.type) {
                    case 'Enum':
                        value = speedFunc.range[(int) remap(speedVal, 0, 4, 0, speedFunc.range.size() - 1)]
                        break
                    case 'Integer':
                        value = remap(speedVal, 0, 4, (int) speedFunc.min, (int) speedFunc.max)
                        break
                    default:
                        LOG.warn "Unknown fan speed function type ${speedFunc}"
                        return
                }
                tuyaSendDeviceCommandsAsync(id, ['code': fanSpeedCode, 'value': value])
            } else if (fanSpeedPercent) {
                Map speedFunc = functions[fanSpeedPercent] ?: defaults[fanSpeedPercent]
                int speedVal = ['low', 'medium-low', 'medium', 'medium-high', 'high'].indexOf(speed)
                int value = remap(speedVal, 0, 4, Integer.valueOf(speedFunc.min), Integer.valueOf(speedFunc.max))
                tuyaSendDeviceCommandsAsync(id, ['code': fanSpeedPercent, 'value': value], ['code': 'switch', 'value': true])
            } else {
                LOG.error "Unable to determine set speed function code in ${functions}"
            }
            break
    }
}

// Component command to set volume change
void componentSetVolume(DeviceWrapper dw, BigDecimal volume) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.volume)
    LOG.info "Starting volume change ${volume} for ${dw}"
    tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': volume])
}

// Component command to flip image
void componentFlipImage(DeviceWrapper dw, Boolean flip) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.basicFlip)
    LOG.info "Starting basic flip change ${flip}"
    tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': flip])
}

void componentMotionTracking(DeviceWrapper dw, Boolean enable) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.motionTracking)
    LOG.info "Starting motion tracking change ${enable}"
    tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': enable])
}

void componentMotionDetection(DeviceWrapper dw, Boolean enable) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.motionSwitch)
    LOG.info "Starting motion detection change ${enable}"
    tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': enable])
}

void componentPatrol(DeviceWrapper dw, Boolean enable) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.cruiseSwitch)
    LOG.info "Starting Patrol change ${flip}"
    tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': enable])
}

// Component command to start level change (up or down)
void componentStartLevelChange(DeviceWrapper dw, String direction) {
    levelChanges[dw.deviceNetworkId] = (direction == 'down') ? -10 : 10
    if (txtEnable) {
        LOG.info "Starting level change ${direction} for ${dw}"
    }
    runInMillis(1000, 'doLevelChange')
}

// Component command to stop level change
void componentStopLevelChange(DeviceWrapper dw) {
    if (txtEnable) {
        LOG.info "Stopping level change for ${dw}"
    }
    levelChanges.remove(dw.deviceNetworkId)
}

// Component command to set position direction
void componentStartPositionChange(DeviceWrapper dw, String direction) {
    switch (direction) {
        case 'open': componentOpen(dw); break
        case 'close': componentClose(dw); break
        default:
            LOG.warn "Unknown position change direction ${direction} for ${dw}"
            break
    }
}

// Component command to stop position change
void componentStopPositionChange(DeviceWrapper dw) {
    Map<String, Map> functions = getFunctions(dw)
    String code = getFunctionCode(functions, tuyaFunctions.control)

    if (code == 'mach_operate') {
        if (txtEnable) {
            LOG.info "Stopping ${dw}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': 'STOP'])
    } else if (code != null) {
        if (txtEnable) {
            LOG.info "Stopping ${dw}"
        }
        tuyaSendDeviceCommandsAsync(dw.getDataValue('id'), ['code': code, 'value': 'stop'])
    } else {
        LOG.error "Unable to determine stop position change function code in ${functions}"
    }
}

// Component command to unlock device
void componentUnlock(DeviceWrapper dw) {
    LOG.warn "componentUnlock not yet supported for ${dw}"
}

// Utility function to handle multiple level changes
void doLevelChange() {
    List active = levelChanges.collect() // copy list locally
    active.each { kv ->
        ChildDeviceWrapper dw = getChildDevice(kv.key)
        if (dw != null) {
            int newLevel = (int) dw.currentValue('level') + kv.value
            if (newLevel < 0) {
                newLevel = 0
            }
            if (newLevel > 100) {
                newLevel = 100
            }
            componentSetLevel(dw, newLevel)
            if (newLevel <= 0 && newLevel >= 100) {
                componentStopLevelChange(device)
            }
        } else {
            levelChanges.remove(kv.key)
        }
    }

    if (!levelChanges.isEmpty()) {
        runInMillis(1000, 'doLevelChange')
    }
}

// Called when the device is started
void initialize() {
    String version = '0.3'
    LOG.info "Driver v${version} initializing"
    state.clear()
    unschedule()

    state.with {
        tokenInfo = [access_token: '', expire: now()] // initialize token
        uuid = state?.uuid ?: UUID.randomUUID().toString()
        driver_version = version
        lang = 'en'
    }

    sendEvent([name: 'deviceCount', value: 0])
    Map datacenter = tuyaCountries.find { c -> c.country == settings.appCountry }
    if (datacenter != null) {
        LOG.info "Setting ${settings.appCountry} datacenter ${datacenter}"
        state.endPoint = datacenter.endpoint
        state.countryCode = datacenter.countryCode
    } else {
        LOG.error 'Country not set in configuration, please update settings'
        sendEvent([name: 'state', value: 'error', descriptionText: 'Country not set in configuration'])
        return
    }

    tuyaAuthenticateAsync()
}

// Called when the device is first created
void installed() {
    LOG.info 'Driver installed'
}

// Called when the device is removed
void uninstalled() {
    LOG.info 'Driver uninstalled'
}

// Called when the settings are updated
void updated() {
    LOG.info 'Driver configuration updated'
    LOG.debug settings
    if (settings.logEnable == true) {
        runIn(1800, 'logsOff')
    }

    initialize()
}

// Called to parse received MQTT data
void parse(String data) {
    Map payload = jsonParser.parseText(interfaces.mqtt.parseMessage(data).payload)
    try {
        Cipher cipher = tuyaGetCipher(Cipher.DECRYPT_MODE)
        Map result = jsonParser.parse(cipher.doFinal(payload.data.decodeBase64()), 'UTF-8')
        if (result.status != null && (result.id != null || result.devId != null)) {
            updateMultiDeviceStatus(result)
        } else if (result.bizCode != null && result.bizData != null) {
            parseBizData(result.bizCode, result.bizData)
        } else {
            LOG.warn "Unsupported mqtt packet: ${result}"
        }
    } catch (javax.crypto.BadPaddingException e) {
        LOG.warn "Decryption error: ${e}"
        sendEvent([name: 'state', value: 'error', descriptionText: e.message])
        runIn(15 + (3 * random.nextInt(3)), initialize)
    }
}

// Called to parse MQTT client status changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            LOG.info "Connected to Tuya MQTT hub"
            sendEvent([name: 'state', value: 'connected', descriptionText: 'Connected to Tuya MQTT hub'])
            runIn(1, 'tuyaHubSubscribeAsync')
            break
        default:
            LOG.error 'MQTT connection error: ' + status
            sendEvent([name: 'state', value: 'disconnected', descriptionText: 'Disconnected from Tuya MQTT hub'])
            runIn(15 + (3 * random.nextInt(3)), initialize)
            break
    }
}

// Command to refresh all devices
void refresh() {
    LOG.info 'Refreshing devices and scenes'
    tuyaGetDevicesAsync()
    tuyaGetHomesAsync()
}

// Command to remove all the child devices
void removeDevices() {
    LOG.info 'Removing all child devices'
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

private static Map<String, Map> getFunctions(DeviceWrapper dw) {
    return jsonCache.computeIfAbsent(dw?.getDataValue('functions') ?: '{}') {
        k -> jsonParser.parseText(k)
    }
}

private static Map getFunction(Map functions, List codes) {
    return functions?.find { f -> f.key in codes }?.value
}

private static String getFunctionCode(Map functions, List codes) {
    return codes?.find { c -> functions?.containsKey(c) }
}

private static Map<String, Map> getStatusSet(DeviceWrapper dw) {
    return jsonCache.computeIfAbsent(dw?.getDataValue('statusSet') ?: '{}') {
        k -> jsonParser.parseText(k)
    }
}

private static BigDecimal remap(BigDecimal oldValue, BigDecimal oldMin, BigDecimal oldMax,
                                BigDecimal newMin, BigDecimal newMax) {
    BigDecimal value = oldValue
    if (value < oldMin) {
        value = oldMin
    }
    if (value > oldMax) {
        value = oldMax
    }
    BigDecimal newValue = ((value - oldMin) / (oldMax - oldMin)) * (newMax - newMin) + newMin
    return newValue.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private static BigDecimal scale(BigDecimal value, Integer scale) {
    return value / Math.pow(10, scale ?: 0)
}

private static String translateColorName(Integer hue, Integer saturation) {
    if (saturation < 1) {
        return 'White'
    }

    switch (hue * 3.6 as int) {
        case 0..15: return 'Red'
        case 16..45: return 'Orange'
        case 46..75: return 'Yellow'
        case 76..105: return 'Chartreuse'
        case 106..135: return 'Green'
        case 136..165: return 'Spring'
        case 166..195: return 'Cyan'
        case 196..225: return 'Azure'
        case 226..255: return 'Blue'
        case 256..285: return 'Violet'
        case 286..315: return 'Magenta'
        case 316..345: return 'Rose'
        case 346..360: return 'Red'
    }

    return ''
}

// define country map
private static Map country(String country, String countryCode, String endpoint = 'https://openapi.tuyaus.com') {
    return [country: country, countryCode: countryCode, endpoint: endpoint]
}

@Field static final List<Map> tuyaCountries = [
        country('Afghanistan', '93', 'https://openapi.tuyaeu.com'),
        country('Albania', '355', 'https://openapi.tuyaeu.com'),
        country('Algeria', '213', 'https://openapi.tuyaeu.com'),
        country('American Samoa', '1-684', 'https://openapi.tuyaeu.com'),
        country('Andorra', '376', 'https://openapi.tuyaeu.com'),
        country('Angola', '244', 'https://openapi.tuyaeu.com'),
        country('Anguilla', '1-264', 'https://openapi.tuyaeu.com'),
        country('Antarctica', '672', 'https://openapi.tuyaus.com'),
        country('Antigua and Barbuda', '1-268', 'https://openapi.tuyaeu.com'),
        country('Argentina', '54', 'https://openapi.tuyaus.com'),
        country('Armenia', '374', 'https://openapi.tuyaeu.com'),
        country('Aruba', '297', 'https://openapi.tuyaeu.com'),
        country('Australia', '61', 'https://openapi.tuyaeu.com'),
        country('Austria', '43', 'https://openapi.tuyaeu.com'),
        country('Azerbaijan', '994', 'https://openapi.tuyaeu.com'),
        country('Bahamas', '1-242', 'https://openapi.tuyaeu.com'),
        country('Bahrain', '973', 'https://openapi.tuyaeu.com'),
        country('Bangladesh', '880', 'https://openapi.tuyaeu.com'),
        country('Barbados', '1-246', 'https://openapi.tuyaeu.com'),
        country('Belarus', '375', 'https://openapi.tuyaeu.com'),
        country('Belgium', '32', 'https://openapi.tuyaeu.com'),
        country('Belize', '501', 'https://openapi.tuyaeu.com'),
        country('Benin', '229', 'https://openapi.tuyaeu.com'),
        country('Bermuda', '1-441', 'https://openapi.tuyaeu.com'),
        country('Bhutan', '975', 'https://openapi.tuyaeu.com'),
        country('Bolivia', '591', 'https://openapi.tuyaus.com'),
        country('Bosnia and Herzegovina', '387', 'https://openapi.tuyaeu.com'),
        country('Botswana', '267', 'https://openapi.tuyaeu.com'),
        country('Brazil', '55', 'https://openapi.tuyaus.com'),
        country('British Indian Ocean Territory', '246', 'https://openapi.tuyaus.com'),
        country('British Virgin Islands', '1-284', 'https://openapi.tuyaeu.com'),
        country('Brunei', '673', 'https://openapi.tuyaeu.com'),
        country('Bulgaria', '359', 'https://openapi.tuyaeu.com'),
        country('Burkina Faso', '226', 'https://openapi.tuyaeu.com'),
        country('Burundi', '257', 'https://openapi.tuyaeu.com'),
        country('Cambodia', '855', 'https://openapi.tuyaeu.com'),
        country('Cameroon', '237', 'https://openapi.tuyaeu.com'),
        country('Canada', '1', 'https://openapi.tuyaus.com'),
        country('Capo Verde', '238', 'https://openapi.tuyaeu.com'),
        country('Cayman Islands', '1-345', 'https://openapi.tuyaeu.com'),
        country('Central African Republic', '236', 'https://openapi.tuyaeu.com'),
        country('Chad', '235', 'https://openapi.tuyaeu.com'),
        country('Chile', '56', 'https://openapi.tuyaus.com'),
        country('China', '86', 'https://openapi.tuyacn.com'),
        country('Christmas Island', '61'),
        country('Cocos Islands', '61'),
        country('Colombia', '57', 'https://openapi.tuyaus.com'),
        country('Comoros', '269', 'https://openapi.tuyaeu.com'),
        country('Cook Islands', '682', 'https://openapi.tuyaus.com'),
        country('Costa Rica', '506', 'https://openapi.tuyaeu.com'),
        country('Croatia', '385', 'https://openapi.tuyaeu.com'),
        country('Cuba', '53'),
        country('Curacao', '599', 'https://openapi.tuyaus.com'),
        country('Cyprus', '357', 'https://openapi.tuyaeu.com'),
        country('Czech Republic', '420', 'https://openapi.tuyaeu.com'),
        country('Democratic Republic of the Congo', '243', 'https://openapi.tuyaeu.com'),
        country('Denmark', '45', 'https://openapi.tuyaeu.com'),
        country('Djibouti', '253', 'https://openapi.tuyaeu.com'),
        country('Dominica', '1-767', 'https://openapi.tuyaeu.com'),
        country('Dominican Republic', '1-809', 'https://openapi.tuyaus.com'),
        country('East Timor', '670', 'https://openapi.tuyaus.com'),
        country('Ecuador', '593', 'https://openapi.tuyaus.com'),
        country('Egypt', '20', 'https://openapi.tuyaeu.com'),
        country('El Salvador', '503', 'https://openapi.tuyaeu.com'),
        country('Equatorial Guinea', '240', 'https://openapi.tuyaeu.com'),
        country('Eritrea', '291', 'https://openapi.tuyaeu.com'),
        country('Estonia', '372', 'https://openapi.tuyaeu.com'),
        country('Ethiopia', '251', 'https://openapi.tuyaeu.com'),
        country('Falkland Islands', '500', 'https://openapi.tuyaus.com'),
        country('Faroe Islands', '298', 'https://openapi.tuyaeu.com'),
        country('Fiji', '679', 'https://openapi.tuyaeu.com'),
        country('Finland', '358', 'https://openapi.tuyaeu.com'),
        country('France', '33', 'https://openapi.tuyaeu.com'),
        country('French Polynesia', '689', 'https://openapi.tuyaeu.com'),
        country('Gabon', '241', 'https://openapi.tuyaeu.com'),
        country('Gambia', '220', 'https://openapi.tuyaeu.com'),
        country('Georgia', '995', 'https://openapi.tuyaeu.com'),
        country('Germany', '49', 'https://openapi.tuyaeu.com'),
        country('Ghana', '233', 'https://openapi.tuyaeu.com'),
        country('Gibraltar', '350', 'https://openapi.tuyaeu.com'),
        country('Greece', '30', 'https://openapi.tuyaeu.com'),
        country('Greenland', '299', 'https://openapi.tuyaeu.com'),
        country('Grenada', '1-473', 'https://openapi.tuyaeu.com'),
        country('Guam', '1-671', 'https://openapi.tuyaeu.com'),
        country('Guatemala', '502', 'https://openapi.tuyaus.com'),
        country('Guernsey', '44-1481'),
        country('Guinea', '224'),
        country('Guinea-Bissau', '245', 'https://openapi.tuyaus.com'),
        country('Guyana', '592', 'https://openapi.tuyaeu.com'),
        country('Haiti', '509', 'https://openapi.tuyaeu.com'),
        country('Honduras', '504', 'https://openapi.tuyaeu.com'),
        country('Hong Kong', '852', 'https://openapi.tuyaus.com'),
        country('Hungary', '36', 'https://openapi.tuyaeu.com'),
        country('Iceland', '354', 'https://openapi.tuyaeu.com'),
        country('India', '91', 'https://openapi.tuyain.com'),
        country('Indonesia', '62', 'https://openapi.tuyaus.com'),
        country('Iran', '98'),
        country('Iraq', '964', 'https://openapi.tuyaeu.com'),
        country('Ireland', '353', 'https://openapi.tuyaeu.com'),
        country('Isle of Man', '44-1624'),
        country('Israel', '972', 'https://openapi.tuyaeu.com'),
        country('Italy', '39', 'https://openapi.tuyaeu.com'),
        country('Ivory Coast', '225', 'https://openapi.tuyaeu.com'),
        country('Jamaica', '1-876', 'https://openapi.tuyaeu.com'),
        country('Japan', '81', 'https://openapi.tuyaus.com'),
        country('Jersey', '44-1534'),
        country('Jordan', '962', 'https://openapi.tuyaeu.com'),
        country('Kazakhstan', '7', 'https://openapi.tuyaeu.com'),
        country('Kenya', '254', 'https://openapi.tuyaeu.com'),
        country('Kiribati', '686', 'https://openapi.tuyaus.com'),
        country('Kosovo', '383'),
        country('Kuwait', '965', 'https://openapi.tuyaeu.com'),
        country('Kyrgyzstan', '996', 'https://openapi.tuyaeu.com'),
        country('Laos', '856', 'https://openapi.tuyaeu.com'),
        country('Latvia', '371', 'https://openapi.tuyaeu.com'),
        country('Lebanon', '961', 'https://openapi.tuyaeu.com'),
        country('Lesotho', '266', 'https://openapi.tuyaeu.com'),
        country('Liberia', '231', 'https://openapi.tuyaeu.com'),
        country('Libya', '218', 'https://openapi.tuyaeu.com'),
        country('Liechtenstein', '423', 'https://openapi.tuyaeu.com'),
        country('Lithuania', '370', 'https://openapi.tuyaeu.com'),
        country('Luxembourg', '352', 'https://openapi.tuyaeu.com'),
        country('Macao', '853', 'https://openapi.tuyaus.com'),
        country('Macedonia', '389', 'https://openapi.tuyaeu.com'),
        country('Madagascar', '261', 'https://openapi.tuyaeu.com'),
        country('Malawi', '265', 'https://openapi.tuyaeu.com'),
        country('Malaysia', '60', 'https://openapi.tuyaus.com'),
        country('Maldives', '960', 'https://openapi.tuyaeu.com'),
        country('Mali', '223', 'https://openapi.tuyaeu.com'),
        country('Malta', '356', 'https://openapi.tuyaeu.com'),
        country('Marshall Islands', '692', 'https://openapi.tuyaeu.com'),
        country('Mauritania', '222', 'https://openapi.tuyaeu.com'),
        country('Mauritius', '230', 'https://openapi.tuyaeu.com'),
        country('Mayotte', '262', 'https://openapi.tuyaeu.com'),
        country('Mexico', '52', 'https://openapi.tuyaus.com'),
        country('Micronesia', '691', 'https://openapi.tuyaeu.com'),
        country('Moldova', '373', 'https://openapi.tuyaeu.com'),
        country('Monaco', '377', 'https://openapi.tuyaeu.com'),
        country('Mongolia', '976', 'https://openapi.tuyaeu.com'),
        country('Montenegro', '382', 'https://openapi.tuyaeu.com'),
        country('Montserrat', '1-664', 'https://openapi.tuyaeu.com'),
        country('Morocco', '212', 'https://openapi.tuyaeu.com'),
        country('Mozambique', '258', 'https://openapi.tuyaeu.com'),
        country('Myanmar', '95', 'https://openapi.tuyaus.com'),
        country('Namibia', '264', 'https://openapi.tuyaeu.com'),
        country('Nauru', '674', 'https://openapi.tuyaus.com'),
        country('Nepal', '977', 'https://openapi.tuyaeu.com'),
        country('Netherlands', '31', 'https://openapi.tuyaeu.com'),
        country('Netherlands Antilles', '599'),
        country('New Caledonia', '687', 'https://openapi.tuyaeu.com'),
        country('New Zealand', '64', 'https://openapi.tuyaus.com'),
        country('Nicaragua', '505', 'https://openapi.tuyaeu.com'),
        country('Niger', '227', 'https://openapi.tuyaeu.com'),
        country('Nigeria', '234', 'https://openapi.tuyaeu.com'),
        country('Niue', '683', 'https://openapi.tuyaus.com'),
        country('North Korea', '850'),
        country('Northern Mariana Islands', '1-670', 'https://openapi.tuyaeu.com'),
        country('Norway', '47', 'https://openapi.tuyaeu.com'),
        country('Oman', '968', 'https://openapi.tuyaeu.com'),
        country('Pakistan', '92', 'https://openapi.tuyaeu.com'),
        country('Palau', '680', 'https://openapi.tuyaeu.com'),
        country('Palestine', '970', 'https://openapi.tuyaus.com'),
        country('Panama', '507', 'https://openapi.tuyaeu.com'),
        country('Papua New Guinea', '675', 'https://openapi.tuyaus.com'),
        country('Paraguay', '595', 'https://openapi.tuyaus.com'),
        country('Peru', '51', 'https://openapi.tuyaus.com'),
        country('Philippines', '63', 'https://openapi.tuyaus.com'),
        country('Pitcairn', '64'),
        country('Poland', '48', 'https://openapi.tuyaeu.com'),
        country('Portugal', '351', 'https://openapi.tuyaeu.com'),
        country('Puerto Rico', '1-787, 1-939', 'https://openapi.tuyaus.com'),
        country('Qatar', '974', 'https://openapi.tuyaeu.com'),
        country('Republic of the Congo', '242', 'https://openapi.tuyaeu.com'),
        country('Reunion', '262', 'https://openapi.tuyaeu.com'),
        country('Romania', '40', 'https://openapi.tuyaeu.com'),
        country('Russia', '7', 'https://openapi.tuyaeu.com'),
        country('Rwanda', '250', 'https://openapi.tuyaeu.com'),
        country('Saint Barthelemy', '590', 'https://openapi.tuyaeu.com'),
        country('Saint Helena', '290'),
        country('Saint Kitts and Nevis', '1-869', 'https://openapi.tuyaeu.com'),
        country('Saint Lucia', '1-758', 'https://openapi.tuyaeu.com'),
        country('Saint Martin', '590', 'https://openapi.tuyaeu.com'),
        country('Saint Pierre and Miquelon', '508', 'https://openapi.tuyaeu.com'),
        country('Saint Vincent and the Grenadines', '1-784', 'https://openapi.tuyaeu.com'),
        country('Samoa', '685', 'https://openapi.tuyaeu.com'),
        country('San Marino', '378', 'https://openapi.tuyaeu.com'),
        country('Sao Tome and Principe', '239', 'https://openapi.tuyaus.com'),
        country('Saudi Arabia', '966', 'https://openapi.tuyaeu.com'),
        country('Senegal', '221', 'https://openapi.tuyaeu.com'),
        country('Serbia', '381', 'https://openapi.tuyaeu.com'),
        country('Seychelles', '248', 'https://openapi.tuyaeu.com'),
        country('Sierra Leone', '232', 'https://openapi.tuyaeu.com'),
        country('Singapore', '65', 'https://openapi.tuyaeu.com'),
        country('Sint Maarten', '1-721', 'https://openapi.tuyaus.com'),
        country('Slovakia', '421', 'https://openapi.tuyaeu.com'),
        country('Slovenia', '386', 'https://openapi.tuyaeu.com'),
        country('Solomon Islands', '677', 'https://openapi.tuyaus.com'),
        country('Somalia', '252', 'https://openapi.tuyaeu.com'),
        country('South Africa', '27', 'https://openapi.tuyaeu.com'),
        country('South Korea', '82', 'https://openapi.tuyaus.com'),
        country('South Sudan', '211'),
        country('Spain', '34', 'https://openapi.tuyaeu.com'),
        country('Sri Lanka', '94', 'https://openapi.tuyaeu.com'),
        country('Sudan', '249'),
        country('Suriname', '597', 'https://openapi.tuyaus.com'),
        country('Svalbard and Jan Mayen', '4779', 'https://openapi.tuyaus.com'),
        country('Swaziland', '268', 'https://openapi.tuyaeu.com'),
        country('Sweden', '46', 'https://openapi.tuyaeu.com'),
        country('Switzerland', '41', 'https://openapi.tuyaeu.com'),
        country('Syria', '963'),
        country('Taiwan', '886', 'https://openapi.tuyaus.com'),
        country('Tajikistan', '992', 'https://openapi.tuyaeu.com'),
        country('Tanzania', '255', 'https://openapi.tuyaeu.com'),
        country('Thailand', '66', 'https://openapi.tuyaus.com'),
        country('Togo', '228', 'https://openapi.tuyaeu.com'),
        country('Tokelau', '690', 'https://openapi.tuyaus.com'),
        country('Tonga', '676', 'https://openapi.tuyaeu.com'),
        country('Trinidad and Tobago', '1-868', 'https://openapi.tuyaeu.com'),
        country('Tunisia', '216', 'https://openapi.tuyaeu.com'),
        country('Turkey', '90', 'https://openapi.tuyaeu.com'),
        country('Turkmenistan', '993', 'https://openapi.tuyaeu.com'),
        country('Turks and Caicos Islands', '1-649', 'https://openapi.tuyaeu.com'),
        country('Tuvalu', '688', 'https://openapi.tuyaeu.com'),
        country('U.S. Virgin Islands', '1-340', 'https://openapi.tuyaeu.com'),
        country('Uganda', '256', 'https://openapi.tuyaeu.com'),
        country('Ukraine', '380', 'https://openapi.tuyaeu.com'),
        country('United Arab Emirates', '971', 'https://openapi.tuyaeu.com'),
        country('United Kingdom', '44', 'https://openapi.tuyaeu.com'),
        country('United States', '1', 'https://openapi.tuyaus.com'),
        country('Uruguay', '598', 'https://openapi.tuyaus.com'),
        country('Uzbekistan', '998', 'https://openapi.tuyaeu.com'),
        country('Vanuatu', '678', 'https://openapi.tuyaus.com'),
        country('Vatican', '379', 'https://openapi.tuyaeu.com'),
        country('Venezuela', '58', 'https://openapi.tuyaus.com'),
        country('Vietnam', '84', 'https://openapi.tuyaus.com'),
        country('Wallis and Futuna', '681', 'https://openapi.tuyaeu.com'),
        country('Western Sahara', '212', 'https://openapi.tuyaeu.com'),
        country('Yemen', '967', 'https://openapi.tuyaeu.com'),
        country('Zambia', '260', 'https://openapi.tuyaeu.com'),
        country('Zimbabwe', '263', 'https://openapi.tuyaeu.com')
]

/**
 *  Driver Capabilities Implementation
 */
private boolean createChildDevices(Map d) {
    Map mapping = mapTuyaCategory(d)
    LOG.debug "Tuya category ${d.category} driver ${mapping}"

    if (mapping.driver != null) {
        createChildDevice("${device.id}-${d.id}", mapping, d)
    }

    if (mapping.devices == null) {
        return false
    }

    // Tuya Device to Multiple Hubitat Devices
    String baseName = d.name
    Map baseFunctions = d.functions
    Map baseStatusSet = d.statusSet
    Map subdevices = mapping.devices.findAll { entry -> entry.key in baseFunctions.keySet() }
    subdevices.each { code, submap ->
        d.name = "${baseName} ${submap.suffix ?: code}"
        d.functions = [(code): baseFunctions[(code)]]
        d.statusSet = [(code): baseStatusSet[(code)]]
        createChildDevice("${device.id}-${d.id}-${code}", [
                namespace: submap.namespace ?: mapping.namespace,
                driver   : submap.driver ?: mapping.driver
        ], d)
    }

    return true
}

private ChildDeviceWrapper createChildDevice(String dni, Map mapping, Map d) {
    ChildDeviceWrapper dw = getChildDevice(dni)
    if (dw == null) {
        LOG.info "Creating device ${d.name} using ${mapping.driver} driver"
        try {
            dw = addChildDevice(mapping.namespace ?: 'hubitat', mapping.driver, dni,
                    [
                            name : d.product_name,
                            label: d.name,
                    ]
            )
        } catch (UnknownDeviceTypeException e) {
            if (mapping.namespace == 'component') {
                LOG.error "${d.name} driver not found, try downloading from " +
                        "https://raw.githubusercontent.com/bradsjm/hubitat-drivers/main/Component/${mapping.driver}"
            } else {
                LOG.exception("${d.name} device creation failed", e)
            }
        }
    }

    String functionJson = JsonOutput.toJson(d.functions)
    jsonCache.put(functionJson, d.functions)
    dw?.with {
        label = label ?: d.name
        updateDataValue 'id', d.id
        updateDataValue 'local_key', d.local_key
        updateDataValue 'product_id', d.product_id
        updateDataValue 'category', d.category
        updateDataValue 'functions', functionJson
        updateDataValue 'statusSet', JsonOutput.toJson(d.statusSet)
        updateDataValue 'online', d.online as String
    }

    return dw
}

private ChildDeviceWrapper createSceneDevice(String homeId, Map scene) {
    String dni = "${device.id}-${scene.scene_id}"
    ChildDeviceWrapper dw = getChildDevice(dni)
    if (dw == null) {
        LOG.info "Creating scene device ${scene.name}"
        try {
            String name = scene.name.replace('\"', '')
            dw = addChildDevice('hubitat', 'Generic Component Switch', dni,
                    [
                            name : name,
                            label: name
                    ]
            )
        } catch (UnknownDeviceTypeException e) {
            LOG.exception("${driver.name} device creation failed", e)
        }
    }

    dw?.with {
        updateDataValue 'homeId', homeId as String
        updateDataValue 'sceneId', scene.scene_id
    }

    return dw
}

/* groovylint-disable-next-line UnusedPrivateMethod */

private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    LOG.info 'Debug logging disabled'
}

private void parseBizData(String bizCode, Map bizData) {
    LOG.debug "${bizCode} ${bizData}"
    switch (bizCode) {
        case 'nameUpdate':
        case 'online':
        case 'offline':
        case 'bindUser':
            refresh()
            break
    }
}

private void updateMultiDeviceStatus(Map d) {
    String base = "${device.id}-${d.id ?: d.devId}"
    Map<String, ChildDeviceWrapper> children = getChildDevices().collectEntries { child -> [(child.deviceNetworkId): child] }
    Map groups = d.status.groupBy { s -> children["${base}-${s.code}"] ?: children[base] }
    LOG.debug "Groups: ${groups}"
    groups.each { dw, states ->
        if (dw != null) {
            // send events to child for parsing
            dw.parse(
                    // create events for the child
                    createEvents(dw, states).findAll { e ->
                        // filter to values that have changed
                        dw.currentValue(e.name) != e.value
                    }
            )
        }
    }
}

/* ---------------------------------------------------
 * Convert Tuya device values to Hubitat device events
 */

/* groovylint-disable-next-line MethodSize */

private List<Map> createEvents(DeviceWrapper dw, List<Map> statusList) {
    String workMode = ''
    Map<String, Map> deviceStatusSet = getStatusSet(dw) ?: getFunctions(dw)
    statusList.each { status ->
        if (status.code in tuyaFunctions.workMode) {
            workMode = status.value
        }
    }
    LOG.debug "${dw} workMode ${workMode}"

    return statusList.collectMany { status ->
        LOG.debug "${dw} status ${status}"

        if (status.code in tuyaFunctions.battery) {
            if (status.code == 'battery_percentage' || status.code == 'va_battery') {
                if (txtEnable) {
                    LOG.info "${dw} battery is ${status.value}%"
                }
                return [[name: 'battery', value: status.value, descriptionText: "battery is ${status.value}%", unit: '%']]
            }
        }

        if (status.code in tuyaFunctions.brightness && workMode != 'colour') {
            Map bright = deviceStatusSet[status.code] ?: defaults[status.code]
            if (bright != null) {
                Integer value = Math.floor(remap((int) status.value, (int) bright.min, (int) bright.max, 0, 100))
                if (txtEnable) {
                    LOG.info "${dw} level is ${value}%"
                }
                return [[name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%", statusCode: status.code]]
            }
        }

        if (status.code in tuyaFunctions.co) {
            String value = status.value == 'alarm' ? 'detected' : 'clear'
            if (txtEnable) {
                LOG.info "${dw} carbon monoxide is ${value}"
            }
            return [[name: 'carbonMonoxide', value: value, descriptionText: "carbon monoxide is ${value}"]]
        }

        if (status.code in tuyaFunctions.co2) {
            Map co2 = deviceStatusSet[status.code] ?: defaults[status.code]
            int value = scale((int) status.value, (int) co2.scale)
            if (txtEnable) {
                LOG.info "${dw} carbon dioxide level is ${value}"
            }
            return [[name: 'carbonDioxide', value: value, unit: 'ppm', descriptionText: "carbon dioxide level is ${value}"]]
        }

        if (status.code in tuyaFunctions.control + tuyaFunctions.workState + tuyaFunctions.situationSet) {
            String value
            switch (status.value) {
                case 'open': value = 'open'; break
                case 'opening': value = 'opening'; break
                case 'close': value = 'closed'; break
                case 'closing': value = 'closing'; break
                case 'FZ': value = 'closed'; break
                case 'ZZ': value = 'open'; break
                case 'stop': value = 'unknown'; break
                case 'fully_open': value = 'open'; break
                case 'fully_close': value = 'closed'; break
                case 'charger_free':
                case 'charger_insert':
                case 'charger_free_fault':
                case 'charger_wait':
                case 'charger_charging':
                case 'charger_pause':
                case 'charger_end':
                case 'charger_fault':
                    value = status.value
            }
            if (value) {
                if (txtEnable) {
                    LOG.info "${dw} control is ${value}"
                }
                return [[name: 'windowShade', value: value, descriptionText: "window shade is ${value}"]]
            }
        }

        if (status.code in tuyaFunctions.ct) {
            Map temperature = deviceStatusSet[status.code] ?: defaults[status.code]
            Integer value = Math.floor(1000000 / remap((int) temperature.max - (int) status.value,
                    (int) temperature.min, (int) temperature.max, minMireds, maxMireds))
            if (txtEnable) {
                LOG.info "${dw} color temperature is ${value}K"
            }
            return [[name           : 'colorTemperature', value: value, unit: 'K',
                     descriptionText: "color temperature is ${value}K"]]
        }

        if (status.code in tuyaFunctions.colour) {
            Map colour = deviceStatusSet[status.code] ?: defaults[status.code]
            Map bright = getFunction(deviceStatusSet, tuyaFunctions.brightness) ?: colour.v
            Map value = status.value == '' ? [h: 100.0, s: 100.0, v: 100.0] :
                    jsonCache.computeIfAbsent(status.value) { k -> jsonParser.parseText(k) }
            Integer hue = Math.floor(remap((int) value.h, (int) colour.h.min, (int) colour.h.max, 0, 100))
            Integer saturation = Math.floor(remap((int) value.s, (int) colour.s.min, (int) colour.s.max, 0, 100))
            Integer level = Math.floor(remap((int) value.v, (int) bright.min, (int) bright.max, 0, 100))
            String colorName = translateColorName(hue, saturation)
            if (txtEnable) {
                LOG.info "${dw} color is h:${hue} s:${saturation} (${colorName})"
            }
            List<Map> events = [
                    [name: 'hue', value: hue, descriptionText: "hue is ${hue}"],
                    [name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}"],
                    [name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}"]
            ]
            if (workMode in ['colour', 'scene']) {
                if (txtEnable) {
                    LOG.info "${dw} level is ${level}%"
                }
                events << [name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%"]
            }
            return events
        }

        if (status.code in tuyaFunctions.contact) {
            String value = status.value ? 'open' : 'closed'
            if (txtEnable) {
                LOG.info "${dw} contact is ${value}"
            }
            return [[name: 'contact', value: value, descriptionText: "contact is ${value}"]]
        }

        if (status.code in tuyaFunctions.fanSpeed) {
            Map speed = deviceStatusSet[status.code] ?: defaults[status.code]
            String fanSwitchCode = getFunctionCode(functions, tuyaFunctions.fanSwitch)
            int value
            if (fanSwitchCode && statusList[fanSwitchCode]) {
                switch (speed.type) {
                    case 'Enum':
                        if (speed.range.indexOf(status.value) > -1) {
                            value = remap(speed.range.indexOf(status.value), 0, speed.range.size() - 1, 0, 4)
                        }
                        break
                    case 'Integer':
                        if (status.value) {
                            int min = (speed.min == null) ? 1 : speed.min
                            int max = (speed.max == null) ? 100 : speed.max
                            value = remap((int) status.value, min, max, 0, 4)
                        }
                        break
                }
                String level = ['low', 'medium-low', 'medium', 'medium-high', 'high'].get(value)
                if (txtEnable) {
                    LOG.info "${dw} speed is ${level}"
                }
                return [
                        [name: 'speed', value: level, descriptionText: "speed is ${level}"],
                        [name: 'switch', value: 'on', descriptionText: 'fan is on']
                ]
            }

            if (txtEnable) {
                LOG.info "${dw} speed is off"
            }
            return [
                    [name: 'speed', value: 'off', descriptionText: 'speed is off'],
                    [name: 'switch', value: 'off', descriptionText: 'fan is off']
            ]
        }

        if (status.code in tuyaFunctions.light || status.code in tuyaFunctions.power) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} switch is ${value}"
            }
            return [[name: 'switch', value: value, descriptionText: "switch is ${value}", statusCode: status.code]]
        }

        if (status.code in tuyaFunctions.powerMeter) {
            Map code = deviceStatusSet[status.code] ?: defaults[status.code]
            String name = 'power'
            String unit = 'kW'
            value = status.value
            return [[name: name, value: value, descriptionText: "${dw} ${name} is ${value} ${unit}", unit: unit]]
        }

        if (status.code in tuyaFunctions.energyMeter) {
            Map code = deviceStatusSet[status.code] ?: defaults[status.code]
            String name = 'energy'
            String unit = 'kWh'
            value = status.value
            return [[name: name, value: value, descriptionText: "${dw} ${name} is ${value} ${unit}", unit: unit]]
        }

        if (status.code in tuyaFunctions.meteringSwitch) {
            Map code = deviceStatusSet[status.code] ?: defaults[status.code]
            String name
            String value
            String unit = ''
            switch (status.code) {
                case 'cur_power':
                    name = 'power'
                    value = scale(status.value, (int) code.scale)
                    unit = 'W'
                    break
                case 'cur_voltage':
                case 'cur_current':
                case 'relay_status':
                case 'light_mode':
                case 'add_ele':
                case 'countdown_1':
                    break
                default:
                    LOG.warn "${dw} unsupported meteringSwitch status.code ${status.code}"
            }
            if (name != null && value != null) {
                if (txtEnable) {
                    LOG.info "${dw} ${name} is ${value} ${unit}"
                }
                return [[name: name, value: value, descriptionText: "${dw} ${name} is ${value} ${unit}", unit: unit]]
            }
        }

        if (status.code in tuyaFunctions.omniSensor) {
            Map code = deviceStatusSet[status.code] ?: defaults[status.code]
            String name
            String value
            String unit = ''
            switch (status.code) {
                case 'bright_value':
                    name = 'illuminance'
                    value = scale(status.value, (int) code.scale)
                    unit = 'Lux'
                    break
                case 'humidity_value':
                case 'va_humidity':
                    value = status.value
                    if (status.code == 'humidity_value') {
                        value = scale(status.value, (int) code.scale)
                    }
                    name = 'humidity'
                    unit = 'RH%'
                    break
                case 'bright_sensitivity':
                case 'sensitivity':
                    name = 'sensitivity'
                    value = scale(status.value, (int) code.scale)
                    unit = '%'
                    break
                case 'shock_state':    // vibration sensor TS0210
                    name = 'motion'    // simulated motion
                    value = 'active'   // no 'inactive' state!
                    unit = ''
                    status.code = 'inactive_state'
                    runIn(5, 'updateMultiDeviceStatus', [data: d])
                    break
                case 'inactive_state': // vibration sensor
                    name = 'motion'    // simulated motion
                    value = 'inactive' // simulated 'inactive' state!
                    unit = ''
                    break
                default:
                    LOG.warn "${dw} unsupported omniSensor status.code ${status.code}"
            }
            if (name != null && value != null) {
                if (txtEnable) {
                    LOG.info "${dw} ${name} is ${value} ${unit}"
                }
                return [[name: name, value: value, descriptionText: "${name} is ${value} ${unit}", unit: unit]]
            }
        }

        if (status.code in tuyaFunctions.pir) {
            String value = status.value == 'pir' ? 'active' : 'inactive'
            if (txtEnable) {
                LOG.info "${dw} motion is ${value}"
            }
            return [[name: 'motion', value: value, descriptionText: "motion is ${value}"]]
        }

        if (status.code in tuyaFunctions.percentControl) {
            if (statusList['code'] == ['fan_speed_percent']) {
                Map speed = deviceStatusSet[status.code] ?: defaults[status.code]
                int min = (speed.min == null) ? 1 : Integer.valueOf(speed.min)
                int max = (speed.max == null) ? 100 : Integer.valueOf(speed.max)
                int value = Math.round(remap(status.value, min, max, 0, 4))
                String speedName = ['low', 'medium-low', 'medium', 'medium-high', 'high'].get(value)
                int level = remap(status.value, min, max, 1, 100)
                if (txtEnable) {
                    LOG.info "${dw} speed is ${level}"
                }
                return [
                        [name: 'speed', value: speedName, descriptionText: "speed is ${speedName}"],
                        [name: 'level', value: level]
                ]
            } else {
                if (txtEnable) {
                    LOG.info "${dw} position is ${status.value}%"
                }
                return [[name: 'position', value: status.value, descriptionText: "position is ${status.value}%", unit: '%']]
            }
        }

        if (status.code in tuyaFunctions.sceneSwitch) {
            String action
            if (status.value in sceneSwitchAction) {
                action = sceneSwitchAction[status.value]
            } else {
                LOG.warn "${dw} sceneSwitch: unknown status.value ${status.value}"
            }

            String value
            if (status.code in sceneSwitchKeyNumbers) {
                value = sceneSwitchKeyNumbers[status.code]
                if (d.productKey == 'vp6clf9d' && status.code == 'switch1_value') {
                    value = '1'                    // correction for TS0044 key #1
                }
            } else {
                LOG.warn "${dw} sceneSwitch: unknown status.code ${status.code}"
            }

            if (value != null && action != null) {
                if (txtEnable) {
                    LOG.info "${dw} buttons ${value} is ${action}"
                }
                return [[name: action, value: value, descriptionText: "button ${value} is ${action}", isStateChange: true]]
            }

            LOG.warn "${dw} sceneSwitch: unknown name ${action} or value ${value}"
        }

        if (status.code in tuyaFunctions.smoke) {
            String value = status.value == 'alarm' ? 'detected' : 'clear'
            if (txtEnable) {
                LOG.info "${dw} smoke is ${value}"
            }
            return [[name: 'smoke', value: value, descriptionText: "smoke is ${value}"]]
        }

        if (status.code in tuyaFunctions.temperature) {
            Map set = deviceStatusSet[status.code] ?: defaults[status.code]
            String value = fromCelcius(scale(status.value, (int) set.scale))
            String unit = location.temperatureScale
            if (txtEnable) {
                LOG.info "${dw} temperature is ${value}${unit} (${status})"
            }
            return [[name: 'temperature', value: value, unit: unit, descriptionText: "temperature is ${value}${unit} (${status})"]]
        }

        if (status.code in tuyaFunctions.temperatureSet) {
            Map set = deviceStatusSet[status.code] ?: defaults[status.code]
            String value = fromCelcius(scale(status.value, (int) set.scale))
            String unit = location.temperatureScale
            if (txtEnable) {
                LOG.info "${dw} heating set point is ${value}${unit} (${status})"
            }
            return [[name: 'heatingSetpoint', value: value, unit: unit, descriptionText: "heating set point is ${value}${unit} (${status})"]]
        }

        if (status.code in tuyaFunctions.water) {
            String value = status.value == 'alarm' ? 'wet' : 'dry'
            if (txtEnable) {
                LOG.info "${dw} water is ${value}"
            }
            return [[name: 'water', value: value, descriptionText: "water is ${value}"]]
        }

        if (status.code in tuyaFunctions.workMode) {
            switch (status.value) {
                case 'white':
                case 'light_white':
                    if (txtEnable) {
                        LOG.info "${dw} color mode is CT"
                    }
                    return [[name: 'colorMode', value: 'CT', descriptionText: 'color mode is CT']]
                case 'colour':
                    if (txtEnable) {
                        LOG.info "${dw} color mode is RGB"
                    }
                    return [[name: 'colorMode', value: 'RGB', descriptionText: 'color mode is RGB']]
                case 'scene':
                    if (txtEnable) {
                        LOG.info "${dw} color mode is EFFECTS"
                    }
                    return [[name: 'colorMode', value: 'EFFECTS', descriptionText: 'color mode is EFFECTS']]
                case 'charge_now':
                case 'charge_pct':
                case 'charge_energy':
                case 'charge_schedule':
                    return [[name: 'workMode', value: status.value, descriptionText: 'Working mode is ' + status.Value]]
            }
        }

        if (status.code in tuyaFunctions.basicFlip) {
            String value = status.value ? 'true' : 'false'
            if (txtEnable) {
                LOG.info "${dw} flip is ${value}"
            }
            return [[name: 'basicFlip', value: value, descriptionText: "flip is ${value}"]]
        }

        if (status.code in tuyaFunctions.basicPrivate) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} basic private is ${value}"
            }
            return [[name: 'privateMode', value: value, descriptionText: "basic private is ${value}"]]
        }

        if (status.code in tuyaFunctions.motionTracking) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} motion tracking is ${value}"
            }
            return [[name: 'motionTracking', value: value, descriptionText: "motion tracking is ${value}"]]
        }

        if (status.code in tuyaFunctions.motionSensitivity) {
            String value = status.value
            String name
            switch (value) {
                case '0':
                    name = 'Low'
                    break
                case '1':
                    name = 'Medium'
                    break
                case '2':
                    name = 'High'
                    break
                default:
                    LOG.warn "${dw} unsupported motion sensitivity value ${value}"
            }
            if (txtEnable) {
                LOG.info "${dw} motion sensitivity  is ${name}"
            }
            return [[name: 'motionSensitivity', value: name, descriptionText: "motion sensitivity is ${name}"]]
        }

        if (status.code in tuyaFunctions.motionSwitch) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} motion detection switch is ${value}"
            }
            return [[name: 'motionDetection', value: value, descriptionText: "motion switch is ${value}"]]
        }

        if (status.code in tuyaFunctions.floodLight) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} flood light switch is ${value}"
            }
            return [[name: 'floodLight', value: value, descriptionText: "flood light is ${value}"]]
        }

        if (status.code in tuyaFunctions.siren) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} siren switch is ${value}"
            }
            return [[name: 'siren', value: value, descriptionText: "siren is ${value}"]]
        }

        if (status.code in tuyaFunctions.volume) {
            String value = status.value
            def level = value.isInteger() ? Integer.valueOf(value) : null
            if (txtEnable) {
                if (level != null && level >= 0 && level <= 100) {
                    LOG.info "${dw} volume level is ${level}%"
                } else {
                    LOG.warn "${dw} invalid volume level is ${value}"
                }
            }
            return [[name: 'volume', value: value, descriptionText: "volume is ${value}"]]
        }

        if (status.code in tuyaFunctions.decibelSwitch) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} sound detection is ${value}"
            }
            return [[name: 'soundDetection', value: value, descriptionText: "sound detection is ${value}"]]
        }

        if (status.code in tuyaFunctions.nightvisionMode) {
            String value = status.value
            String name
            switch (value) {
                case 'auto':
                    name = 'Smart Mode'
                    break
                case 'ir_mode':
                    name = 'Infrared Mode'
                    break
                case 'color_mode':
                    name = 'Color Light Mode'
                    break
                default:
                    LOG.warn "${dw} unsupported nightvision mode value ${value}"
            }
            if (txtEnable) {
                LOG.info "${dw} night mode is ${value}"
            }
            return [[name: 'nightMode', value: name, descriptionText: "night mode is ${name}"]]
        }

        if (status.code in tuyaFunctions.recordSwitch) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} record is ${value}"
            }
            return [[name: 'recording', value: value, descriptionText: "recording is ${value}"]]
        }

        if (status.code in tuyaFunctions.cruiseSwitch) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) {
                LOG.info "${dw} patrol is ${value}"
            }
            return [[name: 'patrol', value: value, descriptionText: "recording is ${value}"]]
        }

        if (status.code in tuyaFunctions.cruiseMode) {
            String value = status.value
            if (txtEnable) {
                LOG.info "${dw} patrol mode value is ${value}"
            }
            String mode = value.split(',')[0][0].toBoolean() ? 'Site Patrol' : 'Panoramic Patrol'
            if (txtEnable) {
                LOG.info "${dw} patrol mode is ${mode}"
            }
            return [[name: 'patrolMode', value: mode, descriptionText: "patrol mode is ${mode}"]]
        }

        if (status.code in tuyaFunctions.humidity) {
            Map set = deviceStatusSet[status.code] ?: defaults[status.code]
            String name
            String value
            String unit = ''
            switch (status.code) {
                case 'temp_indoor':
                    name = 'temperature'
                    value = scale(status.value, (int) set.scale)
                    unit = set.unit
                    break
                case 'shake':
                case 'swing':
                    name = 'swing'
                    value = status.value ? 'on' : 'off'
                    unit = ''
                    break
                case 'lock':
                case 'child_lock':
                    name = 'child_lock'
                    value = status.value ? 'on' : 'off'
                    unit = ''
                    break
                case 'windspeed':
                case 'speed':
                case 'fan_speed_enum':
                    name = 'speed'
                    value = (status.value?.toInteger() == 0 ? 'high' : 'low')
                    unit = ''
                    break
                case 'humidity':
                case 'dehumidify_set_value':
                    name = 'humiditySetpoint'
                    value = scale(status.value, (int) set.scale)
                    unit = 'RH%'
                    break
                case 'envhumid':
                case 'humidity_indoor':
                    name = 'humidity'
                    value = scale(status.value, (int) set.scale)
                    unit = 'RH%'
                    break
                case 'mode':
                    name = 'mode'
                    value = String.valueOf(status.value).isInteger() ? (Integer.valueOf(status.value) == 0 ? 'auto' : 'continuous') : status.value
                    unit = ''
                    break
                case 'anion':
                    name = 'anion'
                    value = status.value ? 'on' : 'off'
                    unit = ''
                    break
                case 'pump':
                    name = 'waterPump'
                    value = status.value ? 'on' : 'off'
                    unit = ''
                    break
                case 'dry':
                    name = 'insideDrying'
                    value = status.value ? 'on' : 'off'
                    unit = ''
                    break
                case 'countdown':
                    name = 'countdown'
                    value = status.value
                    unit = 'Hours'
                    break
                case 'countdown_left':
                    name = 'countdown_left'
                    value = status.value
                    unit = 'Minutes'
                    break
                case 'fault':
                    name = 'fault'
                    switch (status.value) {
                        case 0:
                            value = 'ok'
                            break
                        case 1:
                            value = 'temperature sensor failure (E2)'
                            break
                        case 2:
                            value = 'coil sensor (E1)'
                            break
                        case 4:
                            value = 'defrost (P1)'
                            break
                        case 8:
                            value = 'water full (FL)'
                            break
                        case 16:
                            value = 'low temperature alarm (LO)'
                            break
                        case 32:
                            value = 'high temperature alarm (HI)'
                            break
                        default:
                            value = 'unknown (' + status.value + ')'
                    }
                    unit = ''
                    break
                default:
                    LOG.warn "${dw} unsupported Dehumidifier status.code ${status.code}"
            }

            if (name != null && value != null) {
                if (txtEnable) {
                    LOG.info "${dw} ${name} is ${value} ${unit}"
                }
                return [[name: name, value: value, descriptionText: "${name} is ${value} ${unit}", unit: unit]]
            }
        }

        return []
    }
}

// Convert value to celcius only if we Hubitat is using F scale
private BigDecimal toCelcius(BigDecimal temperature) {
    return (location.temperatureScale == 'F' ? fahrenheitToCelsius(temperature) : temperature).setScale(1, BigDecimal.ROUND_HALF_UP)
}

// Convert value from celcius only if we Hubitat is using F scale
private BigDecimal fromCelcius(BigDecimal temperature) {
    return (location.temperatureScale == 'F' ? celsiusToFahrenheit(temperature) : temperature).setScale(1, BigDecimal.ROUND_HALF_UP)
}

/**
 *  Tuya Open API Authentication
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaAuthenticateAsync() {
    unschedule('tuyaAuthenticateAsync')
    if (settings.username && settings.password && settings.appSchema && state.countryCode) {
        LOG.info "Starting Tuya cloud authentication for ${settings.username}"
        MessageDigest digest = MessageDigest.getInstance('MD5')
        String md5pwd = HexUtils.byteArrayToHexString(digest.digest(settings.password.bytes)).toLowerCase()
        Map query = [
                'grant_type': '1',
        ]
        state.tokenInfo.access_token = ''
        sendEvent([name: 'state', value: 'authenticating', descriptionText: 'Authenticating to Tuya'])
        tuyaGetAsync('/v1.0/token', query, 'tuyaAuthenticateResponse')
    } else {
        sendEvent([name: 'state', value: 'not configured', descriptionText: 'Driver not configured'])
        LOG.error 'Driver must be configured before authentication is possible'
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaAuthenticateResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == false) {
        runIn(15 + (3 * random.nextInt(3)), initialize)
        return
    }

    Map result = response.json.result
    state.tokenInfo = [
            access_token : result.access_token,
            refresh_token: result.refresh_token,
            uid          : result.uid,
            expire       : result.expire_time * 1000 + now(),
    ]
    LOG.info "Received Tuya access token (valid for ${result.expire_time}s)"
    sendEvent([name: 'state', value: 'authenticated', descriptionText: "Received access token ${result.access_token}"])

    // Schedule next authentication
    runIn((int) (result.expire_time * 0.90), 'tuyaRefreshTokenAsync')

    // Get MQTT details
    tuyaGetHubConfigAsync()

    // Get Home Scenes
    tuyaGetHomesAsync()
}

/**
 *  Tuya Open API Device Management
 *  https://developer.tuya.com/en/docs/cloud/
 *
 *  Attributes:
 *      id: Device id
 *      name: Device name
 *      local_key: Key
 *      category: Product category
 *      product_id: Product ID
 *      product_name: Product name
 *      sub: Determine whether it is a sub-device, true-> yes; false-> no
 *      uuid: The unique device identifier
 *      asset_id: asset id of the device
 *      online: Online status of the device
 *      icon: Device icon
 *      ip: Device external IP
 *      time_zone: device time zone
 *      active_time: The last pairing time of the device
 *      create_time: The first network pairing time of the device
 *      update_time: The update time of device status
 *      status: Status set of the device
 */

private Cipher tuyaGetCipher(int mode = Cipher.DECRYPT_MODE) {
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    byte[] cipherKey = state.mqttInfo.password[8..23].bytes
    cipher.init(mode, new SecretKeySpec(cipherKey, 'AES'))
    return cipher
}

private void tuyaGetDevicesAsync(String lastRowKey = '', Map data = [:]) {
    if (!jsonCache.empty) {
        LOG.info 'Clearing json cache'
        jsonCache.clear()
    }

    LOG.info 'Requesting cloud devices batch'
    tuyaGetAsync('/v1.0/iot-01/associated-users/devices', ['last_row_key': lastRowKey], 'tuyaGetDevicesResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaGetDevicesResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == true) {
        Map result = response.json.result
        data.devices = (data.devices ?: []) + result.devices
        LOG.info "Received ${result.devices.size()} cloud devices (has_more: ${result.has_more})"
        if (result.has_more) {
            pauseExecution(1000)
            tuyaGetDevicesAsync(result.last_row_key, data)
            return
        }
    }

    sendEvent([name: 'deviceCount', value: data.devices?.size() as String])
    data.devices.each { d ->
        tuyaGetDeviceSpecificationsAsync(d.id, d)
    }
}

// https://developer.tuya.com/en/docs/cloud/device-control?id=K95zu01ksols7#title-29-API%20address
private void tuyaGetDeviceSpecificationsAsync(String deviceID, Map data = [:]) {
    LOG.info "Requesting cloud device specifications for ${deviceID}"
    tuyaGetAsync("/v1.0/devices/${deviceID}/specifications", null, 'tuyaGetDeviceSpecificationsResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaGetDeviceSpecificationsResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == true) {
        Map result = response.json.result
        data.category = result.category
        if (result.functions != null) {
            data.functions = result.functions.collectEntries { f ->
                Map values = jsonParser.parseText(f.values ?: '{}')
                values.type = f.type
                return [(f.code): values]
            }
        } else {
            data.functions = [:]
        }
        if (result.status != null) {
            data.statusSet = result.status.collectEntries { f ->
                Map values = jsonParser.parseText(f.values ?: '{}')
                values.type = f.type
                return [(f.code): values]
            }
        } else {
            data.statusSet = [:]
        }

        //LOG.debug "Device Data: ${data}"
        createChildDevices(data)
        updateMultiDeviceStatus(data)

        if (device.currentValue('state') != 'ready') {
            sendEvent([name: 'state', value: 'ready', descriptionText: 'Received device data from Tuya'])
        }
    }
}

private void tuyaGetHomesAsync() {
    if (state.tokenInfo?.uid != null) {
        LOG.info 'Requesting Tuya Home list'
        tuyaGetAsync("/v1.0/users/${state.tokenInfo.uid}/homes", null, 'tuyaGetHomesResponse')
    } else {
        LOG.error "Unable to request homes (null uid token: ${state.tokenInfo})"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaGetHomesResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == false) {
        return
    }
    List<Map> homes = response.json.result ?: []
    homes.each { home ->
        tuyaGetScenesAsync(home.home_id)
    }
}

private void tuyaGetScenesAsync(String homeId) {
    LOG.debug "Requesting scenes for home ${homeId}"
    tuyaGetAsync("/v1.0/homes/${homeId}/scenes", null, 'tuyaGetScenesResponse', [homeId: homeId])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaGetScenesResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == false) {
        log.warn 'Check you have the Smart Home Scene Linkage service enabled for your Tuya account'
        return
    }
    state.scenes = state.scenes ?: [:]
    List<Map> scenes = response.json.result ?: []
    scenes.each { scene -> createSceneDevice(data.homeId, scene) }
}

private void tuyaGetStateAsync(String deviceID) {
    LOG.debug "Requesting device ${deviceID} state"
    tuyaGetAsync("/v1.0/devices/${deviceID}/status", null, 'tuyaGetStateResponse', [id: deviceID])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaGetStateResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == false) {
        return
    }
    data.status = response.json.result
    updateMultiDeviceStatus(data)
}

/* groovylint-disable-next-line UnusedPrivateMethod */

private void tuyaRefreshTokenAsync() {
    unschedule('tuyaRefreshTokenAsync')
    LOG.debug 'Refreshing authentication token'
    tuyaGetAsync("/v1.0/token/${state.tokenInfo.refresh_token}", null, 'tuyaRefreshTokenResponse', null)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaRefreshTokenResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == false) {
        tuyaAuthenticateAsync()
        return
    }

    Map result = response.json.result
    state.tokenInfo = [
            access_token : result.access_token,
            refresh_token: result.refresh_token,
            uid          : result.uid,
            expire       : result.expire_time * 1000 + now(),
    ]
    LOG.info "Received Tuya access token (valid for ${result.expire_time}s)"
    sendEvent([name: 'state', value: 'authenticated', descriptionText: "Received refresh access token ${result.access_token}"])
    runIn((int) (result.expire_time * 0.90), 'tuyaRefreshTokenAsync')
    tuyaGetHubConfigAsync()
}

private void tuyaTriggerScene(String homeId, String sceneId) {
    LOG.debug "Triggering scene id ${sceneId}"
    tuyaPostAsync("/v1.0/homes/${homeId}/scenes/${sceneId}/trigger", null, 'tuyaTriggerSceneResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaTriggerSceneResponse(AsyncResponse response, Map data) {
    tuyaCheckResponse(response)
}

private void tuyaSendDeviceCommandsAsync(String deviceID, Map... params) {
    LOG.debug "Sending device ${deviceID} command ${params}"
    if (!state?.tokenInfo?.access_token) {
        LOG.error 'tuyaSendDeviceCommandsAsync Error - Access token is null'
        sendEvent([name: 'state', value: 'error', descriptionText: 'Access token not set (failed login?)'])
        runIn(15 + (3 * random.nextInt(3)), initialize)
        return
    }
    tuyaPostAsync("/v1.0/devices/${deviceID}/commands", ['commands': params], 'tuyaSendDeviceCommandsResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaSendDeviceCommandsResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == true) {
        return
    }
    sendEvent([name: 'state', value: 'error', descriptionText: 'Error sending device command'])
    runIn(15 + (3 * random.nextInt(3)), initialize)
}

/**
 *  Tuya Open API MQTT Hub
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetHubConfigAsync() {
    LOG.info 'Requesting Tuya MQTT configuration'
    Map body = [
            'uid'                  : state.tokenInfo.uid,
            'link_id'              : state.uuid,
            'link_type'            : 'mqtt',
            'topics'               : 'device',
            'msg_encrypted_version': '1.0'
    ]

    tuyaPostAsync('/v1.0/iot-03/open-hub/access-config', body, 'tuyaGetHubConfigResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */

private void tuyaGetHubConfigResponse(AsyncResponse response, Map data) {
    if (tuyaCheckResponse(response) == false) {
        return
    }
    Map result = response.json.result
    if (result.url) {
        state.mqttInfo = result
        tuyaHubConnectAsync()
    } else {
        LOG.warn "Hub response did not contain mqtt details: ${result}"
    }
}

private void tuyaHubConnectAsync() {
    LOG.info "Connecting to Tuya MQTT hub at ${state.mqttInfo.url}"
    try {
        interfaces.mqtt.connect(
                state.mqttInfo.url,
                state.mqttInfo.client_id,
                state.mqttInfo.username,
                state.mqttInfo.password)
    } catch (e) {
        LOG.exception 'MQTT connection error', e
        sendEvent([name: 'state', value: 'error', descriptionText: e.message])
        runIn(15 + (3 * random.nextInt(3)), initialize)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */

private void tuyaHubSubscribeAsync() {
    state.mqttInfo.source_topic.each { t ->
        LOG.info "Subscribing to Tuya MQTT hub ${t.key} topic"
        interfaces.mqtt.subscribe(t.value)
    }

    tuyaGetDevicesAsync()
}

/**
 *  Tuya Open API HTTP REST Implementation
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetAsync(String path, Map query, String callback, Map data = [:]) {
    tuyaRequestAsync('get', path, callback, query, null, data)
}

private void tuyaPostAsync(String path, Map body, String callback, Map data = [:]) {
    tuyaRequestAsync('post', path, callback, null, body ?: [:], data)
}

private void tuyaRequestAsync(String method, String path, String callback, Map query, Map body, Map data) {
    String accessToken = state?.tokenInfo?.access_token ?: ''
    String stringToSign = tuyaGetStringToSign(method, path, query, body)
    if (path.startsWith('/v1.0/token/')) {
        accessToken = ''
    }
    long now = now()
    Map headers = [
            't'                : now,
            'nonce'            : state.uuid,
            'client_id'        : access_id,
            'Signature-Headers': 'client_id',
            'sign'             : tuyaCalculateSignature(accessToken, now, stringToSign),
            'sign_method'      : 'HMAC-SHA256',
            'access_token'     : accessToken,
            'lang'             : state.lang, // use zh for china
            'dev_lang'         : 'groovy',
            'dev_channel'      : 'hubitat',
            'devVersion'       : state.driver_version
    ]

    Map request = [
            uri        : state.endPoint,
            path       : path,
            query      : query,
            contentType: 'application/json',
            headers    : headers,
            body       : JsonOutput.toJson(body),
            timeout    : 5
    ]

    LOG.debug("API ${method.toUpperCase()} ${request}")

    switch (method) {
        case 'get': asynchttpGet(callback, request, data); break
        case 'post': asynchttpPost(callback, request, data); break
    }
}

private boolean tuyaCheckResponse(AsyncResponse response) {
    if (response.hasError()) {
        LOG.error "Cloud request error ${response.getErrorMessage()}"
        sendEvent([name: 'state', value: 'error', descriptionText: response.getErrorMessage()])
        return false
    }

    if (response.status != 200) {
        LOG.error "Cloud request returned HTTP status ${response.status}"
        sendEvent([name: 'state', value: 'error', descriptionText: "Cloud HTTP response ${response.status}"])
        return false
    }

    if (response.json?.success == true) {
        return true
    }

    LOG.error "Cloud API request failed: ${response.data}"
    sendEvent([name: 'state', value: 'error', descriptionText: "${response.json?.msg ?: response.data}"])

    switch (response.json?.code) {
        case 1002: // token is null
        case 1010: // token is expired
        case 1011: // token invalid
        case 1012: // token status invalid
        case 1400: // token invalid
            tuyaAuthenticateAsync()
            break
    }

    return false
}

private String tuyaCalculateSignature(String accessToken, long timestamp, String stringToSign) {
    String message = access_id + accessToken + timestamp.toString() + state.uuid + stringToSign
    Mac sha256HMAC = Mac.getInstance('HmacSHA256')
    sha256HMAC.init(new SecretKeySpec(access_key.bytes, 'HmacSHA256'))
    return HexUtils.byteArrayToHexString(sha256HMAC.doFinal(message.bytes))
}

private String tuyaGetStringToSign(String method, String path, Map query, Map body) {
    String url = query ? path + '?' + query.sort().collect { key, value -> "${key}=${value}" }.join('&') : path
    String headers = 'client_id:' + access_id + '\n'
    String bodyStream = (body == null) ? '' : JsonOutput.toJson(body)
    MessageDigest sha256 = MessageDigest.getInstance('SHA-256')
    String contentSHA256 = HexUtils.byteArrayToHexString(sha256.digest(bodyStream.bytes)).toLowerCase()
    return method.toUpperCase() + '\n' + contentSHA256 + '\n' + headers + '\n' + url
}

@Field private final Map LOG = [
        debug    : { s -> if (settings.logEnable == true) { log.debug(s) } },
        info     : { s -> log.info(s) },
        warn     : { s -> log.warn(s) },
        error    : { s -> log.error(s) },
        exception: { message, exception ->
            List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith('user_app') }
            Integer line = relevantEntries[0]?.lineNumber
            String method = relevantEntries[0]?.methodName
            log.error("${message}: ${exception} at line ${line} (${method})")
            if (settings.logEnable) {
                log.debug("App exception stack trace:\n${relevantEntries.join('\n')}")
            }
        }
].asImmutable()
