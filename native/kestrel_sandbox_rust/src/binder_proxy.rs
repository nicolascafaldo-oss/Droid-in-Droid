use std::collections::BTreeMap;

pub const TX_PING: i32 = 1;
pub const TX_CHECK_SERVICE: i32 = 2;
pub const TX_ADD_SERVICE: i32 = 3;
pub const TX_LIST_SERVICES: i32 = 4;
pub const TX_GET_METRICS: i32 = 5;

#[derive(Debug, Clone)]
pub struct BinderRouter {
    pub vm_id: String,
    pub virtual_node: String,
    pub services: BTreeMap<String, u32>,
    pub next_handle: u32,
    pub total_transactions: u64,
    pub blocked_host_escapes: u64,
    pub last_service: String,
}

#[derive(Debug, Clone)]
pub struct BinderReply {
    pub status: i32,
    pub handle: u32,
    pub response: String,
    pub isolated_node: String,
}

impl BinderRouter {
    pub fn new(vm_id: &str) -> Self {
        let mut services = BTreeMap::new();
        let defaults = [
            "SurfaceFlinger",
            "activity",
            "audio",
            "display",
            "input",
            "package",
            "sensorservice",
            "window",
        ];
        for (idx, name) in defaults.iter().enumerate() {
            services.insert((*name).to_string(), (idx + 1) as u32);
        }
        Self {
            vm_id: vm_id.to_string(),
            virtual_node: format!("/dev/binder_proxy/{vm_id}"),
            services,
            next_handle: 16,
            total_transactions: 0,
            blocked_host_escapes: 0,
            last_service: "servicemanager".to_string(),
        }
    }

    pub fn transact(&mut self, service_name: &str, code: i32, payload: &str) -> BinderReply {
        let trimmed = service_name.trim();

        // Enforce strict Binder domain isolation: block any direct escape attempt to host kernel Binder nodes
        if trimmed.starts_with("/dev/binder") && !trimmed.contains("binder_proxy")
            || trimmed.starts_with("/dev/hwbinder")
            || trimmed.starts_with("/dev/vndbinder")
        {
            self.blocked_host_escapes += 1;
            self.last_service = trimmed.to_string();
            return BinderReply {
                status: -1,
                handle: 0,
                response: format!(
                    "EACCES: blocked direct host binder node '{}' -> use isolated '{}'",
                    trimmed, self.virtual_node
                ),
                isolated_node: self.virtual_node.clone(),
            };
        }

        self.total_transactions += 1;
        self.last_service = if trimmed.is_empty() {
            "servicemanager".to_string()
        } else {
            trimmed.to_string()
        };

        match code {
            TX_PING => BinderReply {
                status: 0,
                handle: 0,
                response: format!(
                    "PONG vm={} node={} services={} tx={}",
                    self.vm_id,
                    self.virtual_node,
                    self.services.len(),
                    self.total_transactions
                ),
                isolated_node: self.virtual_node.clone(),
            },
            TX_CHECK_SERVICE => {
                if let Some(&handle) = self.services.get(trimmed) {
                    BinderReply {
                        status: 0,
                        handle,
                        response: format!(
                            "FOUND service={} handle=#{} proxy={}",
                            trimmed, handle, self.virtual_node
                        ),
                        isolated_node: self.virtual_node.clone(),
                    }
                } else {
                    BinderReply {
                        status: 1,
                        handle: 0,
                        response: format!("NAME_NOT_FOUND service={}", trimmed),
                        isolated_node: self.virtual_node.clone(),
                    }
                }
            }
            TX_ADD_SERVICE => {
                if trimmed.is_empty() {
                    return BinderReply {
                        status: -2,
                        handle: 0,
                        response: "EINVAL: empty service name".to_string(),
                        isolated_node: self.virtual_node.clone(),
                    };
                }
                let handle = *self.services.entry(trimmed.to_string()).or_insert_with(|| {
                    let h = self.next_handle;
                    self.next_handle += 1;
                    h
                });
                BinderReply {
                    status: 0,
                    handle,
                    response: format!(
                        "REGISTERED service={} handle=#{} payload={} proxy={}",
                        trimmed,
                        handle,
                        payload.trim(),
                        self.virtual_node
                    ),
                    isolated_node: self.virtual_node.clone(),
                }
            }
            TX_LIST_SERVICES => {
                let list = self
                    .services
                    .iter()
                    .map(|(k, v)| format!("{k}(#{v})"))
                    .collect::<Vec<_>>()
                    .join(", ");
                BinderReply {
                    status: 0,
                    handle: 0,
                    response: list,
                    isolated_node: self.virtual_node.clone(),
                }
            }
            TX_GET_METRICS => BinderReply {
                status: 0,
                handle: 0,
                response: format!(
                    "tx={} blocked_escapes={} services={} node={}",
                    self.total_transactions,
                    self.blocked_host_escapes,
                    self.services.len(),
                    self.virtual_node
                ),
                isolated_node: self.virtual_node.clone(),
            },
            _ => {
                let handle = self.services.get(trimmed).copied().unwrap_or(0);
                BinderReply {
                    status: 0,
                    handle,
                    response: format!(
                        "BC_TRANSACTION code={} target={} handle=#{} bytes={} proxy={}",
                        code,
                        self.last_service,
                        handle,
                        payload.len(),
                        self.virtual_node
                    ),
                    isolated_node: self.virtual_node.clone(),
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_host_binder_escape_and_registers_isolated_service() {
        let mut router = BinderRouter::new("vm-aosp14-dev");
        let blocked = router.transact("/dev/binder", TX_PING, "");
        assert_eq!(blocked.status, -1);
        assert_eq!(router.blocked_host_escapes, 1);

        let added = router.transact("com.kestrel.audio", TX_ADD_SERVICE, "ring=48000");
        assert_eq!(added.status, 0);
        assert!(added.handle >= 16);

        let found = router.transact("com.kestrel.audio", TX_CHECK_SERVICE, "");
        assert_eq!(found.status, 0);
        assert_eq!(found.handle, added.handle);
    }
}
