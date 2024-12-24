metadata {
    definition(name: 'Generic Component Automotive Charger', namespace: 'component', author: 'Jonathan Bradshaw') {
        capability 'Actuator'
        capability 'Switch'
        capability 'TemperatureMeasurement'
        capability 'EnergyMeter'
        capability 'PowerMeter'
        capability 'VoltageMeasurement'
        capability 'Refresh'

        attribute 'workState', 'enum', ["charger_free","charger_insert","charger_free_fault","charger_wait","charger_charging","charger_pause","charger_end","charger_fault"]
        attribute 'workMode', 'enum', ["charge_now","charge_pct","charge_energy","charge_schedule"]
        attribute 'charge_cur_set', 'number'
        attribute 'fault', 'string'
    }
}

preferences {
    section {
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

// Called when the device is first created
void installed() {
    log.info "${device} driver installed"
}

// Component command to turn on device
void on() {
    parent?.componentOn(device)
    runInMillis(500, 'refresh')
}

// Component command to turn off device
void off() {
    parent?.componentOff(device)
    runInMillis(500, 'refresh')
}

// Component command to refresh device
void refresh() {
    parent?.componentRefresh(device)
}

// parse commands from parent
void parse(List<Map> description) {
    if (logEnable) { log.debug description }
    description.each { d ->
        if (d.descriptionText && txtEnable) { log.info "${device} ${d.descriptionText}" }
        sendEvent(d)
    }
}

// Called when the device is removed
void uninstalled() {
    log.info "${device} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device} driver configuration updated"
    if (logEnable) {
        log.debug settings
        runIn(1800, 'logsOff')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    log.warn "debug logging disabled for ${device}"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )

