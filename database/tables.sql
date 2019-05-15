create table users (
	id serial primary key,
	username varchar(64) not null,
	firstname varchar(32) not null,
	lastname varchar(32) not null,
	mobile varchar(12) not null,
	constraint username_unique unique(username)
);

create table groups (
	id serial primary key,
	ad_group varchar(128) not null,
	code varchar(16) not null,
	sender boolean not null default FALSE,
	constraint code_unique unique(code)
);

create table user_group (
	user_id integer not null,
	group_id integer not null,
	constraint user_group_unique unique(user_id, group_id),
	constraint user_id_fk foreign key (user_id) references users (id) ON DELETE CASCADE,
	constraint group_id_fk foreign key (group_id) references groups (id) ON DELETE CASCADE
);