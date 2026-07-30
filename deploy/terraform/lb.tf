resource "oci_core_public_ip" "public_reserved_ip" {
  compartment_id = var.compartment_ocid
  lifetime       = "RESERVED"

  lifecycle {
    ignore_changes = [private_ip_id]
  }
}

variable "load_balancer_shape_details_maximum_bandwidth_in_mbps" {
  default = 40
}

variable "load_balancer_shape_details_minimum_bandwidth_in_mbps" {
  default = 10
}

resource "oci_load_balancer" "lb" {
  shape          = "flexible"
  compartment_id = var.compartment_ocid

  subnet_ids = [oci_core_subnet.public_subnet.id]

  shape_details {
    maximum_bandwidth_in_mbps = var.load_balancer_shape_details_maximum_bandwidth_in_mbps
    minimum_bandwidth_in_mbps = var.load_balancer_shape_details_minimum_bandwidth_in_mbps
  }

  display_name = "LB ${local.project_name}${local.deploy_id}"

  reserved_ips {
    id = oci_core_public_ip.public_reserved_ip.id
  }
}

resource "oci_load_balancer_backend_set" "lb-backend-set-frontend" {
  name             = "lb-backend-set-frontend"
  load_balancer_id = oci_load_balancer.lb.id
  policy           = "ROUND_ROBIN"

  health_checker {
    port     = "80"
    protocol = "HTTP"
    url_path = "/"
  }
}

resource "oci_load_balancer_backend_set" "lb-backend-set-backend" {
  name             = "lb-backend-set-backend"
  load_balancer_id = oci_load_balancer.lb.id
  policy           = "ROUND_ROBIN"

  # Liveness, not readiness. /actuator/health aggregates every datasource
  # health indicator, so one unreachable production database made it block
  # for ~30 s and return 503 — the load balancer then marked the whole
  # backend unhealthy and served 502 for every /api/* route, taking the UI
  # down entirely over a single degraded tier. /api/v1/health answers from
  # the application alone, so per-component state stays where it belongs:
  # /api/v1/ready, which the UI already renders as status chips.
  health_checker {
    port     = "8080"
    protocol = "HTTP"
    url_path = "/api/v1/health"
  }
}

resource "oci_load_balancer_listener" "lb-listener" {
  load_balancer_id         = oci_load_balancer.lb.id
  name                     = "http"
  default_backend_set_name = oci_load_balancer_backend_set.lb-backend-set-frontend.name
  port                     = 80
  protocol                 = "HTTP"
  routing_policy_name      = oci_load_balancer_load_balancer_routing_policy.routing_policy.name

  connection_configuration {
    # 5 minutes — Select AI Agents team runs can legitimately take 60-120s
    # (multi-task fan-out + RAG + GenAI calls). Default 30s caused 504s.
    idle_timeout_in_seconds = "300"
  }
}

resource "oci_load_balancer_backend" "lb-backend-frontend" {
  load_balancer_id = oci_load_balancer.lb.id
  backendset_name  = oci_load_balancer_backend_set.lb-backend-set-frontend.name
  ip_address       = module.frontend.private_ip
  port             = 80
  backup           = false
  drain            = false
  offline          = false
  weight           = 1
}

resource "oci_load_balancer_backend" "lb-backend-backend" {
  load_balancer_id = oci_load_balancer.lb.id
  backendset_name  = oci_load_balancer_backend_set.lb-backend-set-backend.name
  ip_address       = module.backend.private_ip
  port             = 8080
  backup           = false
  drain            = false
  offline          = false
  weight           = 1
}

resource "oci_load_balancer_load_balancer_routing_policy" "routing_policy" {
  condition_language_version = "V1"
  load_balancer_id           = oci_load_balancer.lb.id
  name                       = "routing_policy"

  rules {
    name      = "routing_to_backend_api"
    condition = "any(http.request.url.path sw (i '/api'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-backend.name
    }
  }

  rules {
    name      = "routing_to_backend_actuator"
    condition = "any(http.request.url.path sw (i '/actuator'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-backend.name
    }
  }

  rules {
    name      = "routing_to_frontend_assets"
    condition = "any(http.request.url.path sw (i '/assets'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-frontend.name
    }
  }

  rules {
    name      = "routing_to_frontend_root"
    condition = "any(http.request.url.path eq (i '/'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-frontend.name
    }
  }
}
