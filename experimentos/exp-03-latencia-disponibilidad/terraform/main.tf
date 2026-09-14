terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  alias  = "primaria"
  region = var.region_primaria
}

provider "aws" {
  alias  = "respaldo"
  region = var.region_respaldo
}

locals {
  contenido_app = file("${path.module}/../servicio-cotizacion/standalone_server.py")
}

# --- Region primaria candidata ---

data "aws_vpc" "primaria" {
  provider = aws.primaria
  default  = true
}

data "aws_subnets" "primaria" {
  provider = aws.primaria
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.primaria.id]
  }
}

data "aws_ami" "primaria" {
  provider    = aws.primaria
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_security_group" "primaria" {
  provider    = aws.primaria
  name        = "exp03-cotizacion-primaria"
  description = "EXP-03: permite trafico HTTP del stub de cotizacion"
  vpc_id      = data.aws_vpc.primaria.id

  ingress {
    from_port   = var.puerto_app
    to_port     = var.puerto_app
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "primaria" {
  provider               = aws.primaria
  ami                    = data.aws_ami.primaria.id
  instance_type          = var.instance_type
  subnet_id              = element(data.aws_subnets.primaria.ids, 0)
  vpc_security_group_ids = [aws_security_group.primaria.id]

  user_data = templatefile("${path.module}/user_data.tftpl", {
    contenido_app = local.contenido_app
    zona          = "primaria-${var.region_primaria}"
  })

  tags = {
    Name       = "exp03-cotizacion-primaria"
    Experimento = "EXP-03"
  }
}

# --- Region de respaldo candidata (usada aqui solo como punto de comparacion de latencia) ---

data "aws_vpc" "respaldo" {
  provider = aws.respaldo
  default  = true
}

data "aws_subnets" "respaldo" {
  provider = aws.respaldo
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.respaldo.id]
  }
}

data "aws_ami" "respaldo" {
  provider    = aws.respaldo
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_security_group" "respaldo" {
  provider    = aws.respaldo
  name        = "exp03-cotizacion-respaldo"
  description = "EXP-03: permite trafico HTTP del stub de cotizacion"
  vpc_id      = data.aws_vpc.respaldo.id

  ingress {
    from_port   = var.puerto_app
    to_port     = var.puerto_app
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "respaldo" {
  provider               = aws.respaldo
  ami                    = data.aws_ami.respaldo.id
  instance_type          = var.instance_type
  subnet_id              = element(data.aws_subnets.respaldo.ids, 0)
  vpc_security_group_ids = [aws_security_group.respaldo.id]

  user_data = templatefile("${path.module}/user_data.tftpl", {
    contenido_app = local.contenido_app
    zona          = "respaldo-${var.region_respaldo}"
  })

  tags = {
    Name       = "exp03-cotizacion-respaldo"
    Experimento = "EXP-03"
  }
}
