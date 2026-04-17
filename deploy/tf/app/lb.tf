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

resource "oci_load_balancer_backend_set" "lb-backend-set-front" {
  name             = "lb-backend-set-front"
  load_balancer_id = oci_load_balancer.lb.id
  policy           = "ROUND_ROBIN"

  health_checker {
    port     = "80"
    protocol = "HTTP"
    url_path = "/"
  }
}

resource "oci_load_balancer_backend_set" "lb-backend-set-back" {
  name             = "lb-backend-set-back"
  load_balancer_id = oci_load_balancer.lb.id
  policy           = "ROUND_ROBIN"

  health_checker {
    port     = "8080"
    protocol = "HTTP"
    url_path = "/actuator/health"
  }
}

resource "oci_load_balancer_listener" "lb-listener" {
  load_balancer_id         = oci_load_balancer.lb.id
  name                     = "http"
  default_backend_set_name = oci_load_balancer_backend_set.lb-backend-set-front.name
  port                     = 80
  protocol                 = "HTTP"
  routing_policy_name      = oci_load_balancer_load_balancer_routing_policy.routing_policy.name

  connection_configuration {
    idle_timeout_in_seconds = "30"
  }
}

resource "oci_load_balancer_backend" "lb-backend-front" {
  load_balancer_id = oci_load_balancer.lb.id
  backendset_name  = oci_load_balancer_backend_set.lb-backend-set-front.name
  ip_address       = module.front.private_ip
  port             = 80
  backup           = false
  drain            = false
  offline          = false
  weight           = 1
}

resource "oci_load_balancer_backend" "lb-backend-back" {
  load_balancer_id = oci_load_balancer.lb.id
  backendset_name  = oci_load_balancer_backend_set.lb-backend-set-back.name
  ip_address       = module.back.private_ip
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
    name      = "routing_to_back_api"
    condition = "any(http.request.url.path sw (i '/api'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-back.name
    }
  }

  rules {
    name      = "routing_to_back_actuator"
    condition = "any(http.request.url.path sw (i '/actuator'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-back.name
    }
  }

  rules {
    name      = "routing_to_front_assets"
    condition = "any(http.request.url.path sw (i '/assets'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-front.name
    }
  }

  rules {
    name      = "routing_to_front_root"
    condition = "any(http.request.url.path eq (i '/'))"
    actions {
      name             = "FORWARD_TO_BACKENDSET"
      backend_set_name = oci_load_balancer_backend_set.lb-backend-set-front.name
    }
  }
}
